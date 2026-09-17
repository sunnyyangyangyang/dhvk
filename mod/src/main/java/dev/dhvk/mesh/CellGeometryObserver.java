package dev.dhvk.mesh;

/**
 * 几何变更观察者（S4 RTAS 出货口门；现在只登记不实现——克制，规格 §10②）。
 *
 * <p>届时 Caustica 集成实现本接口：注册表 diff → BLAS / TLAS。绑定层零新增
 * 机制：AS 描述符与现有 image/buffer 描述符共享同一 resource heap 写路径
 * （评审核实，规格 §10② 备注）。
 */
public interface CellGeometryObserver {

    /**
     * cell 几何更新（重提取后）。
     *
     * @param key       cell 坐标（含 level）
     * @param region    几何字节所在堆子区
     * @param generation tile 代数（= 重提取触发值，规格 §4.2）
     */
    void onCellUpdated(CellKey key, HeapSubregion region, long generation);

    /** cell 几何驱逐（堆子区回收；重入带内 = 重上传，不重提取）。 */
    void onCellEvicted(CellKey key);
}
