INSERT INTO "system_settings" ("key_name", "value_json", "description")
VALUES (
  'ai.model_priority',
  '{"models": [
    {"provider": "GEMINI", "model": "gemma-4-26b-a4b-it"},
    {"provider": "OPENROUTER", "model": "google/gemma-4-31b-it:free"},
    {"provider": "GROQ", "model": "meta-llama/llama-4-scout-17b-16e-instruct"},
    {"provider": "GEMINI", "model": "gemini-2.5-flash"},
    {"provider": "OPENROUTER", "model": "nvidia/nemotron-3-super-120b-a12b:free"},
    {"provider": "OPENROUTER", "model": "openai/gpt-oss-120b:free"}
  ]}'::jsonb,
  'Thứ tự ưu tiên (waterfall) của các mô hình AI. Model đầu tiên là primary, các model còn lại là fallback theo thứ tự.'
)
ON CONFLICT ("key_name") DO UPDATE SET
  "description" = EXCLUDED."description";
