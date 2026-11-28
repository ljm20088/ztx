-- V7：元数据发布动作必须能写入不可变的版本历史。
-- V4 已发布迁移保持不变；通过后续迁移向前兼容地扩展动作约束。
ALTER TABLE meta.document_definition_history
    DROP CONSTRAINT IF EXISTS document_definition_history_action_check;

ALTER TABLE meta.document_definition_history
    ADD CONSTRAINT document_definition_history_action_check
    CHECK (action IN ('CREATE', 'UPDATE', 'COPY', 'VALIDATE', 'VALIDATION_FAILED', 'PUBLISH'));
