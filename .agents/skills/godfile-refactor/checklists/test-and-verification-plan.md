# Test & Verification Checklist for God-File Refactoring

## 1. Backend Verification
- [ ] Pure parsing utilities verified in `AiToolSupport`
- [ ] Domain tool classes compile cleanly with zero unused imports
- [ ] `TaskPilotAiTools` properly delegates to domain beans
- [ ] Constructor backward-compatibility maintained for test suites
- [ ] Unit test run: `./mvnw clean test -pl taskpilot-ai` passes 100%
- [ ] All 37 unit tests in `taskpilot-ai` pass without regressions

## 2. Frontend Verification
- [ ] Helper functions in `aiChatHelpers.ts` match original behavior
- [ ] All subcomponents properly typed with TypeScript
- [ ] `npm run build` passes with zero errors
- [ ] Stream handling, token buffering, and typewriter effect verified
- [ ] Dynamic forms and confirmation modals retain interactive behavior

## 3. Container & Deploy Verification
- [ ] Hugging Face verification script passes: `bash scripts/verify-hf-deploy.sh`
- [ ] UID 1000 container user permission verified
- [ ] Port 7860 binding verified
- [ ] Zero crash loops on missing cloud env variables
