package dev.dhvk.heap;

import dev.dhvk.VkHandles;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.EXTDescriptorHeap;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBindHeapInfoEXT;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorMappingSourceConstantOffsetEXT;
import org.lwjgl.vulkan.VkDescriptorMappingSourceDataEXT;
import org.lwjgl.vulkan.VkDescriptorSetAndBindingMappingEXT;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceAddressRangeEXT;
import org.lwjgl.vulkan.VkHostAddressRangeEXT;
import org.lwjgl.vulkan.VkMappedMemoryRange;
import org.lwjgl.vulkan.VkMemoryAllocateFlagsInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceDescriptorHeapPropertiesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkResourceDescriptorDataEXT;
import org.lwjgl.vulkan.VkResourceDescriptorInfoEXT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * S1 descriptor heap 本体: 一段 GPU 内存 = 全体 cell 的描述符表(堆不是对象, 是内存范围)。
 *
 * <p>持久堆(init 建一次, 不重建不搬家) + 每帧整表绑定(vkCmdBindResourceHeapEXT, 命令级
 * mapping 链把 1 代 UNIFORM_BUFFER 绑定重定向到堆源) + dirty 描述符重写。
 *
 * <p>内存类型与写入路径按笔记 §3/§8(5090 优先 DEVICE_LOCAL|HOST_VISIBLE ReBAR 窗口):
 * <ul>
 *   <li>路径 A = host-visible 映射直写(descSize==16 时 payload 编码 = 裸
 *       VkDeviceAddressRangeEXT; 非 coherent 写后显式 vkFlushMappedMemoryRanges);</li>
 *   <li>路径 B = vkWriteResourceDescriptorsEXT(驱动序列化, hostRange 指堆的 host 窗口槽位;
 *       纯 device-local 时 256B host-visible coherent scratch 垫底);</li>
 *   <li>env {@code DHVK_HEAPWRITE=A|B} 强制, 缺省 auto = host-visible 且 descSize==16 走 A 否则 B。</li>
 * </ul>
 */
public final class DescriptorHeap implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(DescriptorHeap.class);

    // 1.4.357 头文件 sType 字面量(LWJGL 3.4.1 STYPE 静态字段半初始化, 用字面量 —— 任务 1 同课)
    static final int STYPE_BIND_HEAP_INFO = 1000135003;
    static final int STYPE_RESOURCE_DESCRIPTOR_INFO = 1000135002;
    static final int STYPE_SET_BINDING_MAPPING = 1000135005;
    static final int STYPE_HEAP_PROPS = 1000135008;

    // 内存属性位 = 2026 新值空间 javap 实锤: DEVICE_LOCAL=1, HOST_VISIBLE=2, HOST_COHERENT=4.
    // run13 病灶 ①: 手写旧 spec 字面量(1/2/4 反序)把 dump 标签打歪; DEVICE_ADDRESS 属性位在
    // 2026 spec 已删除(所有内存类型都可带 DEVICE_ADDRESS 分配位, 类型选择不再按它过滤)
    static final int MEM_DEVICE_LOCAL = VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
    static final int MEM_HOST_VISIBLE = VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
    static final int MEM_HOST_COHERENT = VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;

    /** 1 代 buffer 描述符类型 = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER。 */
    static final int DESC_TYPE_UNIFORM_BUFFER = 6;

    /**
     * 一个堆源映射: 哪个 set 的哪个绑定从堆取。S1 全部 = push set(0) 单绑定
     * + UNIFORM_BUFFER 资源掩码 + 堆内恒定偏移(HEAP_WITH_CONSTANT_OFFSET_EXT)。
     *
     * @param firstBinding 合并 set layout 里的绑定索引
     * @param heapOffset 表内**绝对**槽位偏移(bind 时按 rangeOffset 换算为相对值)
     */
    public record Mapping(int firstBinding, long heapOffset) {
    }

    private final long sizeBytes;
    private final long cellCapacity;
    private final long slotAlignment;
    private final long descSize;
    private final boolean coherent;
    private final boolean writePathA;
    private final boolean hostVisible;

    private final long buffer;
    private final long memory;
    private final long baseDeviceAddr;
    private final long baseHostAddr; // 非 host-visible 时 0
    private final int chosenTypeIndex;
    private final int chosenTypeFlags;
    private long scratchBuffer; // 路径 B 且非 host-visible 时的 host 模板区(0 = 无)
    private long scratchMemory;
    private long scratchHostAddr;
    private volatile boolean closed;
    // run15 根因: pNext 链节点必须常驻 native heap(VVL 持有指向它的裸指针, 栈上节点会悬空)
    private long flagsInfoNode;

    public DescriptorHeap(long cellCapacity, int slotsPerCell) {
        if (VkHandles.deviceWrapper == null || VkHandles.pdevWrapper == null) {
            throw new IllegalStateException("[dhvk] descriptor heap init: vk device/pdev wrapper not captured");
        }
        // 1) 驱动侧属性(槽对齐/描述符尺寸/堆上限)
        long bufDescAlignment;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceDescriptorHeapPropertiesEXT hp = VkPhysicalDeviceDescriptorHeapPropertiesEXT.calloc(stack)
                    .sType(STYPE_HEAP_PROPS);
            VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(stack)
                    .sType$Default()
                    .pNext(hp);
            VK11.vkGetPhysicalDeviceProperties2(VkHandles.pdevWrapper, props2);
            bufDescAlignment = hp.bufferDescriptorAlignment();
            this.descSize = hp.bufferDescriptorSize();
            long maxResourceHeapSize = hp.maxResourceHeapSize();
            long resourceHeapAlignment = hp.resourceHeapAlignment();
            // 2) 预算(R4: 超 maxResourceHeapSize → 降 cell 容量并 log)
            long cells = cellCapacity;
            long size = HeapLayout.totalBytes(cells, slotsPerCell, bufDescAlignment);
            if (size > maxResourceHeapSize) {
                cells = Math.max(1L, maxResourceHeapSize / ((long) slotsPerCell * bufDescAlignment));
                size = HeapLayout.totalBytes(cells, slotsPerCell, bufDescAlignment);
                LOGGER.warn("[dhvk] descriptor heap budget downgraded: {} -> {} cells (maxResourceHeapSize={}B)",
                        cellCapacity, cells, maxResourceHeapSize);
            }
            if (resourceHeapAlignment > 1L) {
                size = (size + resourceHeapAlignment - 1L) / resourceHeapAlignment * resourceHeapAlignment;
            }
            this.cellCapacity = cells;
            this.sizeBytes = size;
            this.slotAlignment = bufDescAlignment;
            // 3) 内存类型一次性 dump + 选择(§3: DEVICE_LOCAL|HOST_VISIBLE 优先, HOST_COHERENT 更优)
            int typeIndex;
            int typeFlags;
            VkPhysicalDeviceMemoryProperties mp;
            VkPhysicalDeviceMemoryProperties2 mp2 = VkPhysicalDeviceMemoryProperties2.calloc(stack).sType$Default();
            VK11.vkGetPhysicalDeviceMemoryProperties2(VkHandles.pdevWrapper, mp2);
            mp = mp2.memoryProperties();
            int typeCount = mp.memoryTypeCount();
            LOGGER.info("[dhvk] memory types ({}):", typeCount);
            for (int i = 0; i < typeCount; i++) {
                int flags = mp.memoryTypes().get(i).propertyFlags();
                LOGGER.info("[dhvk]   type[{}]: {}{}{} flags=0x{} heap[{}]", i,
                        (flags & MEM_DEVICE_LOCAL) != 0 ? "DEVICE_LOCAL " : "",
                        (flags & MEM_HOST_VISIBLE) != 0 ? "HOST_VISIBLE " : "",
                        (flags & MEM_HOST_COHERENT) != 0 ? "HOST_COHERENT" : "",
                        Integer.toHexString(flags),
                        mp.memoryTypes().get(i).heapIndex());
            }
            // 4) 堆 buffer(raw vkCreateBuffer, usage = 堆位 + 传输位)
            boolean noFlags = "1".equals(System.getenv("DHVK_NOFLAGS"));
            boolean heapSda = "1".equals(System.getenv("DHVK_HEAPSDA"));
            long[] buf = new long[1];
            int heapUsage = EXTDescriptorHeap.VK_BUFFER_USAGE_DESCRIPTOR_HEAP_BIT_EXT
                    | VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
            if (heapSda) {
                heapUsage |= VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
            }
            int rc = VK10.vkCreateBuffer(VkHandles.deviceWrapper,
                    VkBufferCreateInfo.calloc(stack).sType$Default()
                            .size(size)
                            .usage(heapUsage)
                            .sharingMode(0),
                    null, buf);
            check("vkCreateBuffer(heap)", rc);
            this.buffer = buf[0];
            // 5) 内存需求 → 选 type → 分配/绑定 → 设备基址
            VkMemoryRequirements req = VkMemoryRequirements.calloc(stack);
            VK10.vkGetBufferMemoryRequirements(VkHandles.deviceWrapper, this.buffer, req);
            typeIndex = pickMemoryType(req.memoryTypeBits(), typeCount, mp);
            typeFlags = mp.memoryTypes().get(typeIndex).propertyFlags();
            this.chosenTypeIndex = typeIndex;
            this.chosenTypeFlags = typeFlags;
            this.hostVisible = (typeFlags & MEM_HOST_VISIBLE) != 0;
            this.coherent = (typeFlags & MEM_HOST_COHERENT) != 0;
            long[] mem = new long[1];
            // run13 病灶(VUID-vkBindBufferMemory-buffer-11408): 堆 usage 位的 buffer, 内存必须带
            // VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT(2026 值 = 2) —— 堆描述符 payload 存的就是设备地址.
            // 2026 值空间里 flags 字段已移入 pNext 链的 VkMemoryAllocateFlagsInfo(VK11 扩展结构体,
            // sType 用 VK11 字面量 —— 新生代结构体有 LWJGL STYPE 半初始化陷阱, 不用 sType$Default)
            // run15/run16a 根因(VVL 1.4.341 内部 SIGSEGV): VVL 把调用方栈上 pNext 节点的裸指针存进
            // 自己的状态, 后续 vkGetBufferDeviceAddress 时再走一遍链 —— MemoryStack 弹出后链头悬空,
            // walker 解引用非空链头读 sType 即 SEGV(反汇编: test %rbx,%rbx; je skip). run16a
            // (DHVK_NOFLAGS=1 摘链)实证 NULL 链头被优雅处理(bind 仅报 VUID-11408 消息, 无崩溃).
            // 修复: 节点常驻 native heap(nmemCalloc 零初始化+16B 对齐, 2026 LWJGL 小写 nmem* API),
            // close() 时才释放.
            // 遗留二分开关: DHVK_NOFLAGS=1 摘链(诊断); DHVK_HEAPSDA=1 堆叠加 SHADER_DEVICE_ADDRESS(诊断)
            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                    .allocationSize(req.size())
                    .memoryTypeIndex(typeIndex);
            if (!noFlags) {
                flagsInfoNode = MemoryUtil.nmemCalloc(32, 16);
                VkMemoryAllocateFlagsInfo afi = VkMemoryAllocateFlagsInfo.create(flagsInfoNode)
                        .sType(VK11.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO)
                        .flags(VK12.VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT);
                allocInfo.pNext(afi);
            }
            int rc2 = VK10.vkAllocateMemory(VkHandles.deviceWrapper, allocInfo, null, mem);
            check("vkAllocateMemory(heap)", rc2);
            this.memory = mem[0];
            check("vkBindBufferMemory(heap)",
                    VK10.vkBindBufferMemory(VkHandles.deviceWrapper, this.buffer, this.memory, 0L));
            // run17/run18 根因(VVL 1.4.341): 1 代 vkGetBufferDeviceAddress 对 descriptor-heap usage
            // buffer 的验证路径持有失效内部指针(pre/post bind 均复现, 与我们 pNext 链的存放位置无关)
            // → 全部设备地址查询统一走 2 代 vkGetBufferDeviceAddress2(VVL 独立验证函数).
            // 诊断开关: DHVK_SKIPHEAPADDR=1 跳过查询(base=0 占位), 判定崩溃范围
            if ("1".equals(System.getenv("DHVK_SKIPHEAPADDR"))) {
                this.baseDeviceAddr = 0L;
                LOGGER.warn("[dhvk] DHVK_SKIPHEAPADDR=1: vkGetBufferDeviceAddress(heap) skipped (diagnostic)");
            } else {
                this.baseDeviceAddr = bdaAddress2(this.buffer);
            }
        }
        // 6) host 映射窗口(路径 A 直写需要; 路径 B 的 hostRange 在 host-visible 时也用它)
        long hostAddr = 0L;
        if (this.hostVisible) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer p = stack.mallocPointer(1);
                int rc = VK10.vkMapMemory(VkHandles.deviceWrapper, this.memory, 0L, sizeBytes, 0, p);
                check("vkMapMemory(heap)", rc);
                hostAddr = p.get(0);
            }
        }
        this.baseHostAddr = hostAddr;
        // 7) 写入路径裁决(env 强制 / auto)
        String forced = System.getenv("DHVK_HEAPWRITE");
        boolean wantA = "A".equals(forced);
        boolean wantB = "B".equals(forced);
        this.writePathA = (wantA || wantB) ? wantA : (this.hostVisible && this.descSize == 16L);
        // 8) 路径 B 且非 host-visible → host 模板 scratch(256B HOST_VISIBLE|COHERENT)
        if (!this.writePathA && !this.hostVisible) {
            allocateHostScratch();
        }
        LOGGER.info("[dhvk] descriptor heap ready: {}B ({} cells x {} slots), bufDescAlign={}B descSize={}B, "
                + "memType[{}]={} coherent={}, writePath={}, baseDev=0x{}, baseHost=0x{}",
                sizeBytes, cellCapacity, 2, slotAlignment, descSize, chosenTypeIndex, typeFlagsName(chosenTypeFlags),
                coherent,
                writePathA ? "A(host-direct)" : "B(vkWriteResourceDescriptorsEXT)",
                Long.toHexString(baseDeviceAddr), Long.toHexString(baseHostAddr));
    }

    /** 纯 device-local 驱动垫底: host-visible coherent 256B scratch, 供写 API 的 hostRange 指向。 */
    private void allocateHostScratch() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long[] sb = new long[1];
            int rc = VK10.vkCreateBuffer(VkHandles.deviceWrapper,
                    VkBufferCreateInfo.calloc(stack).sType$Default()
                            .size(256L)
                            .usage(VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                            .sharingMode(0),
                    null, sb);
            check("vkCreateBuffer(scratch)", rc);
            scratchBuffer = sb[0];
            VkMemoryRequirements req = VkMemoryRequirements.calloc(stack);
            VK10.vkGetBufferMemoryRequirements(VkHandles.deviceWrapper, scratchBuffer, req);
            VkPhysicalDeviceMemoryProperties2 mp2 = VkPhysicalDeviceMemoryProperties2.calloc(stack).sType$Default();
            VK11.vkGetPhysicalDeviceMemoryProperties2(VkHandles.pdevWrapper, mp2);
            VkPhysicalDeviceMemoryProperties mp = mp2.memoryProperties();
            int typeCount = mp.memoryTypeCount();
            int typeIndex = -1;
            for (int pass = 0; pass < 2 && typeIndex < 0; pass++) {
                for (int i = 0; i < typeCount; i++) {
                    if ((req.memoryTypeBits() & (1 << i)) == 0) {
                        continue;
                    }
                    int flags = mp.memoryTypes().get(i).propertyFlags();
                    if ((flags & MEM_HOST_VISIBLE) != 0 && (pass == 1 || (flags & MEM_HOST_COHERENT) != 0)) {
                        typeIndex = i;
                        break;
                    }
                }
            }
            if (typeIndex < 0) {
                throw new IllegalStateException("[dhvk] no HOST_VISIBLE memory type for write scratch");
            }
            long[] sm = new long[1];
            int rc2 = VK10.vkAllocateMemory(VkHandles.deviceWrapper,
                    VkMemoryAllocateInfo.calloc(stack).sType$Default()
                            .allocationSize(req.size())
                            .memoryTypeIndex(typeIndex),
                    null, sm);
            check("vkAllocateMemory(scratch)", rc2);
            scratchMemory = sm[0];
            check("vkBindBufferMemory(scratch)",
                    VK10.vkBindBufferMemory(VkHandles.deviceWrapper, scratchBuffer, scratchMemory, 0L));
            PointerBuffer p = stack.mallocPointer(1);
            int rc3 = VK10.vkMapMemory(VkHandles.deviceWrapper, scratchMemory, 0L, 256L, 0, p);
            check("vkMapMemory(scratch)", rc3);
            scratchHostAddr = p.get(0);
        }
    }

    /** 首选 DEVICE_LOCAL|HOST_VISIBLE|COHERENT → DEVICE_LOCAL|HOST_VISIBLE → 纯 DEVICE_LOCAL(垫底)。 */
    private static int pickMemoryType(int bits, int typeCount, VkPhysicalDeviceMemoryProperties mp) {
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < typeCount; i++) {
                if ((bits & (1 << i)) == 0) {
                    continue;
                }
                int flags = mp.memoryTypes().get(i).propertyFlags();
                if ((flags & (MEM_DEVICE_LOCAL | MEM_HOST_VISIBLE)) == (MEM_DEVICE_LOCAL | MEM_HOST_VISIBLE)
                        && (pass == 1 || (flags & MEM_HOST_COHERENT) != 0)) {
                    return i;
                }
            }
        }
        for (int i = 0; i < typeCount; i++) {
            if ((bits & (1 << i)) != 0 && (mp.memoryTypes().get(i).propertyFlags() & MEM_DEVICE_LOCAL) != 0) {
                return i;
            }
        }
        throw new IllegalStateException("[dhvk] no DEVICE_LOCAL memory type for heap buffer (bits=0x"
                + Integer.toHexString(bits) + ")");
    }

    /** cell 的第 slot 个槽位在堆内的字节偏移。 */
    public long slotOffsetOf(long cell, int slot) {
        return HeapLayout.slotOffset(cell, slot, slotAlignment);
    }

    /** 堆总大小(字节)。 */
    public long sizeBytes() {
        return sizeBytes;
    }

    /** 设备侧表基址 + 偏移。 */
    public long deviceAddressAt(long offset) {
        return baseDeviceAddr + offset;
    }

    /** 写一个 buffer 描述符(payload = arena 子区的设备地址区间)到 cell 的槽位。 */
    public void writeBufferDescriptor(long cell, int slot, long deviceAddress, long rangeSize) {
        if (closed) {
            throw new IllegalStateException("[dhvk] descriptor heap already closed");
        }
        long off = HeapLayout.slotOffset(cell, slot, slotAlignment);
        if (off + descSize > sizeBytes) {
            throw new IllegalStateException("[dhvk] descriptor slot out of table: cell=" + cell + " slot=" + slot);
        }
        if (writePathA) {
            // A: host 直写 —— 仅当 descSize==16(auto 裁决保证)时 payload 编码 = 裸地址区间
            long host = baseHostAddr + off;
            MemoryUtil.memPutLong(host, deviceAddress);
            MemoryUtil.memPutLong(host + 8, rangeSize);
            if (!coherent) {
                flushRange(off, 16L);
            }
            return;
        }
        // B: 驱动序列化(hostRange = 堆 host 窗口槽位 / host scratch)
        long hostAddr = hostVisible ? baseHostAddr + off : scratchHostAddr;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // LWJGL 3.4.1 字段名 = address$/size(VkDeviceAddressRangeEXT); VkHostAddressRangeEXT
            // 的 size 只有 getter → 走 n- 静态 setter
            VkDeviceAddressRangeEXT range = VkDeviceAddressRangeEXT.calloc(stack)
                    .address$(deviceAddress)
                    .size(rangeSize);
            VkResourceDescriptorDataEXT data = VkResourceDescriptorDataEXT.calloc(stack).pAddressRange(range);
            VkResourceDescriptorInfoEXT info = VkResourceDescriptorInfoEXT.calloc(stack)
                    .sType(STYPE_RESOURCE_DESCRIPTOR_INFO)
                    .type(DESC_TYPE_UNIFORM_BUFFER)
                    .data(data);
            // address 字段生成为指针成员(3.4.1 代码生成怪癖, 无 long setter) → 裸偏移写
            // (头文件序: address@0, size@8; 驱动按 host 指针值用)
            VkHostAddressRangeEXT hostRange = VkHostAddressRangeEXT.calloc(stack);
            MemoryUtil.memPutLong(hostRange.address(), hostAddr);
            MemoryUtil.memPutLong(hostRange.address() + 8L, descSize);
            int rc = EXTDescriptorHeap.nvkWriteResourceDescriptorsEXT(
                    VkHandles.deviceWrapper, 1, info.address(), hostRange.address());
            check("vkWriteResourceDescriptorsEXT", rc);
        }
    }

    /**
     * 每帧一次: 在给定 CBU 上绑堆范围 + 命令级 mapping 链(1 代 set layout 类型不变, 笔记 §1/§8)。
     *
     * @param commandBufferAddr 笔记 §2 钉死的发射点(encoder 当前 CBU, setPipeline 后 drawIndexed 前)
     * @param rangeOffset 堆内范围起点(表内绝对偏移)
     * @param rangeSize 范围大小
     * @param mappings 绑定→堆源重定向(每 binding 一个, heapOffset = 表内绝对槽位偏移)
     */
    public void bind(long commandBufferAddr, long rangeOffset, long rangeSize, Mapping... mappings) {
        if (closed) {
            return;
        }
        VkDevice device = VkHandles.deviceWrapper;
        if (device == null || commandBufferAddr == 0L) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // mapping 链: 逆序挂 pNext, mappings[0] 成为链头
            long chain = 0L;
            for (int i = mappings.length - 1; i >= 0; i--) {
                Mapping m = mappings[i];
                long ho = m.heapOffset() - rangeOffset;
                VkDescriptorMappingSourceConstantOffsetEXT co = VkDescriptorMappingSourceConstantOffsetEXT.calloc(stack)
                        .heapOffset((int) ho)
                        .heapArrayStride(0);
                VkDescriptorMappingSourceDataEXT sd = VkDescriptorMappingSourceDataEXT.calloc(stack).constantOffset(co);
                VkDescriptorSetAndBindingMappingEXT mp = VkDescriptorSetAndBindingMappingEXT.calloc(stack)
                        .sType(STYPE_SET_BINDING_MAPPING)
                        .pNext(chain)
                        .descriptorSet(0) // push 描述符集 = VK_NULL_HANDLE(layoutSet 0)
                        .firstBinding(m.firstBinding())
                        .bindingCount(1)
                        .resourceMask(0x20) // VK_SPIRV_RESOURCE_TYPE_UNIFORM_BUFFER_BIT_EXT
                        .source(0)         // VK_DESCRIPTOR_MAPPING_SOURCE_HEAP_WITH_CONSTANT_OFFSET_EXT
                        .sourceData(sd);
                chain = mp.address();
            }
            VkDeviceAddressRangeEXT range = VkDeviceAddressRangeEXT.calloc(stack)
                    .address$(baseDeviceAddr + rangeOffset)
                    .size(rangeSize);
            VkBindHeapInfoEXT bindInfo = VkBindHeapInfoEXT.calloc(stack)
                    .sType(STYPE_BIND_HEAP_INFO)
                    .pNext(chain)
                    .heapRange(range)
                    .reservedRangeOffset(0L)
                    .reservedRangeSize(0L); // 笔记 §6 裁决: S1 一律 0/0
            EXTDescriptorHeap.nvkCmdBindResourceHeapEXT(
                    new VkCommandBuffer(commandBufferAddr, device), bindInfo.address());
        }
    }

    private void flushRange(long offset, long size) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // LWJGL 3.4.1 字段名 = size(非 rangeSize)
            VkMappedMemoryRange r = VkMappedMemoryRange.calloc(stack)
                    .sType$Default()
                    .memory(this.memory)
                    .offset(offset)
                    .size(size);
            int rc = VK10.vkFlushMappedMemoryRanges(VkHandles.deviceWrapper, r);
            check("vkFlushMappedMemoryRanges", rc);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        VkDevice device = VkHandles.deviceWrapper;
        if (device == null) {
            return;
        }
        // 有序释放(VVL 对象追踪): 先 unmap/destroy buffer, 后 free memory
        if (hostVisible) {
            VK10.vkUnmapMemory(device, memory);
        }
        if (scratchMemory != 0L) {
            if (scratchHostAddr != 0L) {
                VK10.vkUnmapMemory(device, scratchMemory);
            }
            VK10.vkFreeMemory(device, scratchMemory, null);
        }
        VK10.vkDestroyBuffer(device, buffer, null);
        VK10.vkFreeMemory(device, memory, null);
        if (scratchBuffer != 0L) {
            VK10.vkDestroyBuffer(device, scratchBuffer, null);
        }
        if (flagsInfoNode != 0L) {
            // VVL 的链 walker 可能直到 vkFreeMemory 才走完状态, 节点放最后放
            MemoryUtil.nmemFree(flagsInfoNode);
            flagsInfoNode = 0L;
        }
        LOGGER.info("[dhvk] descriptor heap closed ({}B, table @0x{})", sizeBytes, Long.toHexString(baseDeviceAddr));
    }

    /** 2 代 vkGetBufferDeviceAddress(VVL 1.4.341 的 1 代 walker 有内部悬空指针 bug, 见笔记 run17/18)。 */
    private static long bdaAddress2(long vkBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferDeviceAddressInfo info = VkBufferDeviceAddressInfo.calloc(stack)
                    .sType$Default()
                    .buffer(vkBuffer);
            return VK12.vkGetBufferDeviceAddress(VkHandles.deviceWrapper, info);
        }
    }

    private static void check(String op, int rc) {
        if (rc != 0) {
            throw new IllegalStateException("[dhvk] " + op + " failed: rc=" + rc);
        }
    }

    private static String typeFlagsName(int flags) {
        StringBuilder sb = new StringBuilder();
        if ((flags & MEM_DEVICE_LOCAL) != 0) {
            sb.append("DEVICE_LOCAL|");
        }
        if ((flags & MEM_HOST_VISIBLE) != 0) {
            sb.append("HOST_VISIBLE|");
        }
        if ((flags & MEM_HOST_COHERENT) != 0) {
            sb.append("HOST_COHERENT|");
        }
        if ((flags & ~(MEM_DEVICE_LOCAL | MEM_HOST_VISIBLE | MEM_HOST_COHERENT)) != 0) {
            sb.append("0x").append(Integer.toHexString(flags));
        }
        return sb.toString();
    }
}
