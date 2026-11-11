# Flyway — tài liệu nào dùng lúc nào

- **Kết quả đã phát hành:** [Recovery release 10/11/2026](RECOVERY_RELEASE_2026-11-10.md). Nguồn đã tiếp nhận V42, validate đạt và app mới đã kiểm tra live; không dùng báo cáo trước đó để suy ra tình trạng hiện tại.
- **Làm việc hằng ngày với DB chung:** [quy trình nhóm](../../development/FLYWAY_TEAM_WORKFLOW.md), [skill độc lập](../../../.agents/skills/taskpilot-flyway-team/SKILL.md) và `python tools/flyway-team/check.py` từ gốc backend. Version live phải đọc lại, không hardcode từ báo cáo.
- **Thao tác recovery có chủ đích:** [runbook](RECOVERY_RUNBOOK.md). Không chạy adoption/baseline cho một migration thông thường.
- **Diễn biến sự cố 09/11:** [điều tra](INVESTIGATION_2026-11-09.md), [đánh giá phương án](SOLUTION_ASSESSMENT_2026-11-09.md), [minh chứng trước sửa](TEST_EVIDENCE_2026-11-09.md), [hiện thực đầu tiên](RECOVERY_IMPLEMENTATION_2026-11-09.md), [hotfix](HOTFIX_RELEASE_2026-11-09.md), [theo dõi sau hotfix](RECOVERY_FOLLOWUP_2026-11-09.md).
- **Review trước cutover 10/11:** [phát hiện độc lập](INDEPENDENT_REVIEW_2026-11-10.md), [bản sửa](REVIEW_FIXES_2026-11-10.md). Đây là bản ghi tại thời điểm kiểm tra, không thay thế kết quả phát hành cuối.

Giữ báo cáo cũ để đối chiếu nguyên nhân và bằng chứng; các câu “chưa cutover”, “chưa deploy”, “opt-in” trong phần lịch sử chỉ đúng tại mốc đó. Không đưa dump, log chứa dữ liệu thật, credential hoặc artifact lab vào Git. RLS/quyền có sẵn và các tích hợp cloud chưa kiểm thử thật vẫn là phần việc riêng được ghi trong báo cáo phát hành.
