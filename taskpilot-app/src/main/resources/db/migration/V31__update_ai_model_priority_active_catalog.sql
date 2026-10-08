UPDATE "system_settings"
SET "value_json" = '{
  "models": [
    {"provider": "GEMINI", "model": "gemini-3.5-flash"},
    {"provider": "GROQ", "model": "openai/gpt-oss-120b"},
    {"provider": "GEMINI", "model": "gemini-2.5-flash"},
    {"provider": "GROQ", "model": "openai/gpt-oss-20b"},
    {"provider": "OPENROUTER", "model": "openai/gpt-oss-120b:free"},
    {"provider": "OPENROUTER", "model": "nvidia/nemotron-3-super-120b-a12b:free"}
  ]
}'::jsonb,
"description" = 'Thứ tự ưu tiên (waterfall) của các mô hình AI đang hoạt động.'
WHERE "key_name" = 'ai.model_priority';
