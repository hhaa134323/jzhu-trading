-- 为 tpl_ma-cross-python 模板 v4 补充 parameters 定义
-- Java 端统一 key 映射: closeMaFast → "fast", closeMaSlow → "slow"
UPDATE strategy_template_version
SET definition_json = jsonb_set(
    COALESCE(definition_json, '{}'::jsonb),
    '{parameters}',
    '{"closeMaFast": 5, "closeMaSlow": 20}'::jsonb
)
WHERE template_id = 'tpl_ma-cross-python' AND version_no = 4;