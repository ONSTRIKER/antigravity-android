---
name: career-edtech-copilot
description: Career acceleration, school/education operations, document analytics, and EdTech engineering copilot. Activate when analyzing PDFs, spreadsheets, baseline assessments, attendance/GoGuardian data, generating Word/Excel/PowerPoint/Google Workspace deliverables, logging career accomplishments ("brag sheet"), preparing evaluations/resumes, or shipping EdTech software projects.
---

# Career, Education Operations & EdTech Engineering Copilot (`career-edtech-copilot`)

Combine local document/data processing, Google Workspace + Microsoft Office document generation, cross-app mobile workflows, and persistent career tracking to save hours on daily job tasks and compound long-term career leverage.

## 1. Local Document, PDF, OCR & Spreadsheet Analytics

Use the pre-installed CLI tools and dedicated Office Python virtual environment (`$HOME/.gemini/assistant-venv/bin/python`):
- **PDF Extraction & Layout:** `pdftotext -layout "<file.pdf>" -` (`poppler`), `pdfimages`, or `pypdf` inside `$HOME/.gemini/assistant-venv/bin/python`.
- **Scanned Docs / Whiteboards / Screenshots OCR:** `tesseract "<image.png>" stdout` (combine with `magick` from `imagemagick` for contrast/deskew preprocessing).
- **Microsoft Office & Data Files (`.docx`, `.xlsx`, `.pptx`, `.csv`):**
  Always invoke `$HOME/.gemini/assistant-venv/bin/python` with `python-docx`, `openpyxl`, `python-pptx`, and `pypdf` (plus `sqlite3` for fast SQL rollups over CSV/JSON exports) to read or generate polished Word memos, Excel trackers/gradebooks, and PowerPoint decks directly in `/storage/emulated/0/Documents/` or `/storage/emulated/0/Download/`.
- **Google Workspace Sync:** Pair local analysis with the `gdrive`, `gdocs`, `gsheets`, `gslides`, and `gcalendar` skills to publish polished cloud deliverables directly to Drive.

## 2. School & EdTech Operational Workflows

- **Assessment & Baseline Comparisons (e.g., Pre/Post Baselines, Cohort Growth):**
  Extract student/cohort tables, compute growth deltas, mastery bands, and targeted intervention groups, and output both an executive summary table in chat and a clean `.xlsx` / Google Sheet.
- **Cross-App Mobile Operations:**
  When tasks span mobile-only portals or apps (e.g., **NYCPS Attendance**, **Google Classroom**, **Microsoft Outlook**, **Wispr Flow**), combine `android-phone-agent` (`android_apps`, `android_ui_tree`, `android_ui_act`) with `wispr-flow` voice notes to turn quick spoken thoughts between classes/meetings into completed reports, logged attendance reminders, or drafted emails.

## 3. Career Wins & "Brag Sheet" Vault (`android_memory`)

High-impact work gets forgotten at evaluation or resume time unless captured in the moment:
- Whenever the user ships a new software capability (e.g., `epic-hall-pass` features, `antigravity-android` releases), completes a major school/team initiative, or achieves measurable student/operational gains, log a structured STAR entry via:
  ```json
  {
    "action": "upsert",
    "category": "career_win",
    "title": "Shipped Epic Hall Pass Digital Workflow",
    "content": "Designed and deployed real-time hall pass tracking web app for EPIC High School South, streamlining student accountability and staff visibility.",
    "tags": ["edtech", "leadership", "engineering", "impact"]
  }
  ```
- On request (*"prep my observation reflection"*, *"update my resume bullets"*, *"summarize my wins this semester"*), query `android_memory(action="search", category="career_win")` and synthesize tailored, metric-backed bullets.
