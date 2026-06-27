-- Remove the old ai.selected_model and ai.provider_priority settings (consolidated into ai.model_priority)
DELETE FROM "system_settings" WHERE "key_name" IN ('ai.selected_model', 'ai.provider_priority');
