INSERT INTO "system_settings" ("key_name", "value_json", "description")
VALUES 
('ai.model_priority', '{"models": [{"provider": "GEMINI", "model": "gemma-4-26b-a4b-it"}, {"provider": "GROQ", "model": "meta-llama/llama-4-scout-17b-16e-instruct"}, {"provider": "OPENROUTER", "model": "google/gemma-4-31b-it:free"}]}'::jsonb, 'Thứ tự ưu tiên của các mô hình AI (Model Waterfall) khi định tuyến câu hỏi và xử lý tác vụ của Copilot.')
ON CONFLICT ("key_name") DO NOTHING;
