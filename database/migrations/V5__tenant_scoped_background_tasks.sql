-- 控制面后台任务记录可信租户归属；Worker 必须重新查询控制面验证状态和路由。
ALTER TABLE platform.background_task
    ADD COLUMN tenant_id UUID REFERENCES platform.tenant(id) ON DELETE RESTRICT;
CREATE INDEX idx_background_task_tenant_status
    ON platform.background_task(tenant_id, status, created_at DESC)
    WHERE tenant_id IS NOT NULL;
