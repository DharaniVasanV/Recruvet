# Fake Job Post System - Project Report

## Project Title

Fake Job Post Detection and Verification System

## Project Overview

The Fake Job Post System is a Spring Boot web application designed to identify suspicious job posts, fake offer letters, internship scams, and company impersonation attempts. The system allows users to submit job descriptions as text, images, or PDF documents and receive a risk analysis report. It combines machine learning, OCR, document forensics, company lookup, public review reputation analysis, AI-based verification, and crowdsourced confirmed-outcome reports.

The main purpose of the project is to protect job seekers and students from fraudulent job opportunities. Fake job posts often use urgent wording, unrealistic offers, unofficial communication channels, registration fees, fake company names, and forged offer letters. This system helps detect such signals and presents the evidence in a user-readable format instead of only showing a final score.

The project has grown from a simple fake job classifier into a complete verification platform. It includes normal user login, Google OAuth login, job analysis dashboard, prediction history, PDF and image analysis, AI entity cleanup, company website and LinkedIn lookup, review reputation scoring, Groq AI verification, document forensics, evidence trails, WhatsApp intake, crowdsourced scam confirmation, admin moderation, and a college placement-cell dashboard for bulk screening.

## Objectives

The main objective is to provide a reliable web-based platform where users can check whether a job post or offer letter appears genuine or suspicious. The system aims to combine multiple independent verification signals so the final result is more explainable than a basic ML classifier. It also aims to help institutions such as colleges and placement cells screen many job postings before forwarding them to students.

Another important objective is to build a community-driven ground-truth database. Users can report confirmed outcomes such as confirmed scam, confirmed legitimate, or unsure. Admins can moderate these reports, and once enough distinct users confirm a company as fraudulent, the system can show a separate community warning on future analyses.

## Technology Stack

The backend is built using Java 17 and Spring Boot 3. Spring MVC handles web routes, Thymeleaf renders the frontend pages, Spring Security manages login and access control, Spring OAuth2 Client handles Google login, and Spring Data JPA manages database persistence. PostgreSQL is used as the main database.

The machine learning part uses a Python script that loads a trained model and TF-IDF vectorizer from `.pkl` files. The Java backend calls this Python script during analysis. OCR is performed using Tesseract OCR, which extracts text from job post images and scanned offer letters. PDF processing is handled through Apache PDFBox, including text extraction and lower-level metadata used for document forensics.

External services are used for advanced verification. Gemini is used for entity cleanup and review evidence analysis. Groq is used for AI-based scam verification and fraud reasoning. Serper is used to perform Google search for official websites, LinkedIn pages, and review evidence. WhoisXML API can be used to check if a company domain was recently registered. Meta WhatsApp Cloud API is used for WhatsApp-based scam checking. SMTP is optionally used to send weekly digest emails to college placement officers.

## System Architecture

The system follows a layered Spring Boot architecture. Controllers receive user requests and render pages or accept webhooks. Services contain the business logic such as detection, OCR, ML inference, review analysis, entity extraction, company lookup, and outcome aggregation. Repositories provide database access through Spring Data JPA. DTO classes carry structured results between services and controllers. Thymeleaf templates display the dashboard, login, result page, admin moderation page, and TPO dashboard.

The central analysis flow starts in `DashboardController` for web submissions or `WhatsAppWebhookController` for WhatsApp submissions. Both reuse the same detection pipeline in `DetectionService`. This service coordinates OCR or PDF text extraction, ML scoring, entity extraction, Gemini cleanup, company lookup, review verification, document forensics, Groq AI verification, domain age checks, employee mismatch checks, and evidence trail aggregation. The result is then saved as a `Prediction` record and displayed to the user.

## Main Workflow

When a user submits a text job post, the text is passed directly into the detection pipeline. When a user uploads an image, the image is stored temporarily and Tesseract OCR extracts text. When a user uploads a PDF, PDFBox extracts the text and document metadata. The extracted or submitted text is then scored by the ML model. The system extracts entities such as company names, emails, phone numbers, organizations, and contact persons. Gemini may improve these extracted entities by cleaning noisy OCR output.

After company detection, Serper is used to search for the official website and LinkedIn page. Public review evidence is collected from sources such as Reddit, Indeed, and Glassdoor search results where available. Gemini analyzes this evidence and produces a review reputation score. Groq performs a deeper AI verification by checking suspicious wording, company legitimacy clues, scam reports, and fraud indicators. The final weighted score is based on ML score, review reputation score, and AI verification score.

For document inputs, the forensics service checks additional structural signals such as inconsistent company names, missing offer-letter fields, font anomalies, and possible signature or stamp presence. These document signals are shown separately as forensic flags.

Finally, the result page displays the risk score, score breakdown, review analysis, AI report, company verification links, extracted information, evidence trail, and a user feedback form for reporting confirmed outcomes.

## Scoring Approach

The project uses a multi-signal scoring approach. The ML model score contributes 40 percent of the final score. The review reputation score contributes 40 percent. The Groq AI verification score contributes 20 percent. Pattern detection was removed as a separate score because the ML model already captures text-pattern risk.

The system also contains signals that are shown as evidence or warnings but are not silently added into the final weighted score. Examples include document forensics flags, domain age warnings, employee-count mismatch, and crowdsourced confirmed-outcome warnings. This design keeps the score explainable while still surfacing important evidence.

## Major Features

### User Authentication

The system supports normal signup and login using email and password. Passwords are encoded using BCrypt. It also supports Google OAuth2 login. Google users are created or linked by email and Google ID.

### Job Post Analysis

Users can analyze job descriptions by pasting text or uploading files. The result includes a total risk score, breakdown, recommendation, company verification, evidence trail, and detailed AI/review reports.

### OCR and PDF Support

Image input is processed using Tesseract OCR. PDF input is processed using PDFBox. This allows the system to analyze screenshots, posters, scanned offer letters, and PDF offer letters.

### Machine Learning Scoring

The ML service runs `scripts/predict_score.py`, which loads the trained fake job model and TF-IDF vectorizer from the `models` directory. If Python or model execution fails, the Java service falls back to a rule-based heuristic score so the app continues working.

### Entity Extraction

The entity extraction service identifies emails, phone numbers, organizations, company names, and contact persons from the extracted text. It includes OCR correction logic for common email and domain extraction errors.

### Gemini Entity Cleanup

Gemini improves extracted data by removing incorrect organizations, cleaning contact person lists, and choosing the most likely company name. This is especially useful for OCR text where names and words may be noisy.

### Company Lookup

The company lookup service uses Serper to search for official websites and LinkedIn pages. It avoids directly using mismatched links and falls back to Google or LinkedIn search pages when the search result does not confidently match the company.

### Review Reputation Analysis

The review verification service searches public platforms for company reputation signals. It gathers evidence from search results and scraped pages where practical, sends evidence to Gemini, and produces positive and negative review counts with a review risk score.

### Groq AI Verification

Groq performs fraud reasoning based on the job text and company context. It returns a risk score, summary, red flags, and possible scam report references.

### Document Forensics

Document forensics checks offer-letter structure. It looks for missing standard fields, company-name inconsistencies across header/body/signature zones, font inconsistencies, and basic signature/stamp indicators.

### Evidence Trail

The project uses `RedFlagCheck` as a unified evidence item. Signals from ML, reviews, Groq AI, document forensics, domain age checks, and employee mismatch checks are aggregated into one evidence trail so users can understand why a score or warning appeared.

### Crowdsourced Outcome Reports

After viewing a result, users can report what actually happened. Reports can be marked as confirmed scam, confirmed legitimate, or unsure. Admins moderate these reports. Approved reports are aggregated by normalized company identifier. Once at least three distinct users confirm a scam for the same company, future results show a distinct community warning.

### Admin Moderation

Admins can view pending outcome reports and approve or reject them. This prevents unverified user reports from immediately influencing future warnings.

### TPO Dashboard

The Training and Placement Officer dashboard is built for colleges. A verified institution can have TPO users who upload CSV files containing multiple job postings. The system processes the batch asynchronously, analyzes each posting, and displays institution-scoped results. Repeat offender companies are highlighted using the crowdsourced reputation aggregate.

### WhatsApp Intake

The WhatsApp webhook allows users to send suspicious job messages or images to a WhatsApp Cloud API number. The app processes the text or downloads the image, performs OCR if needed, runs the same detection pipeline, stores the result, and replies with a short risk verdict and result link.

### Render Deployment

The project includes a Dockerfile for Render deployment. The Docker image installs Java runtime, Python, Tesseract OCR, and Python ML dependencies. Render environment variables provide database URLs, API keys, OAuth credentials, and optional integrations.

## Database Design

The `users` table stores application users, login details, roles, and optional institution links. The `predictions` table stores each analysis result including job text preview, score, ML score, review score, Groq score, company details, source type, uploaded file reference, evidence JSON, and public token. The `institution` table stores colleges or organizations with verified email domains. The `batch_job` table stores TPO CSV batch processing status. The `outcome_report` table stores user-submitted confirmed outcomes. The `company_reputation_aggregate` table stores aggregated community reputation counts.

## Important Environment Variables

`DATABASE_URL` connects the app to PostgreSQL. `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET` enable Google login. `GEMINI_API_KEY` enables entity cleanup and review evidence analysis. `SERPER_API_KEY` enables Google search for company lookup and reputation evidence. `GROQ_API_KEY` enables AI scam verification. `APP_PYTHON_COMMAND` tells Java how to run Python. `APP_TESSERACT_COMMAND` tells Java how to run Tesseract OCR. `APP_PUBLIC_BASE_URL` is used for public links such as WhatsApp result links. WhatsApp variables are used only when Meta Cloud API integration is enabled. SMTP and TPO digest variables are optional and only used for weekly placement-cell email summaries.

## File Purpose Summary

### Application and Configuration

`FakeJobPostSystemApplication.java` is the Spring Boot entry point. It enables scheduling and async execution. `SecurityConfig.java` configures login pages, route permissions, Google OAuth, admin access, TPO access, and public webhook routes. `DataSourceConfig.java` builds the PostgreSQL datasource from `DATABASE_URL`, including Render-style URLs. `WebConfig.java` exposes uploaded files as static resources. `GlobalControllerAdvice.java` provides shared model attributes to templates. `PredictionSchemaMaintenance.java` performs small startup schema fixes for existing databases.

### Controllers

`AuthController.java` handles login, signup, Google-login redirect, and TPO signup validation. `DashboardController.java` handles the normal user dashboard, job detection form, result page rendering, saved prediction viewing, public prediction viewing, and prediction deletion. `ApiController.java` provides prediction history data for charts. `WhatsAppWebhookController.java` handles Meta WhatsApp webhook verification and incoming message processing. `OutcomeReportController.java` handles user-submitted confirmed outcome reports. `AdminOutcomeReportController.java` provides admin moderation for outcome reports. `TpoDashboardController.java` provides the college placement-cell dashboard and CSV upload flow.

### Services

`DetectionService.java` is the central coordinator for the analysis pipeline. `MlInferenceService.java` calls the Python model script and provides fallback scoring. `OcrService.java` runs Tesseract OCR. `PdfTextExtractionService.java` extracts text and metadata from PDFs. `DocumentForensicsService.java` detects structural forgery signals in documents. `EntityExtractionService.java` extracts emails, phones, persons, organizations, and company names. `GeminiEntityCleanupService.java` uses Gemini to clean extracted entities. `CompanyLookupService.java` finds official websites, LinkedIn pages, and employee-count mismatch evidence. `ReviewVerificationService.java` gathers review evidence and uses Gemini to produce review reputation scoring. `GroqService.java` performs AI fraud verification using Groq. `DomainAgeCheckService.java` checks company domain registration age using WhoisXML. `OutcomeReportService.java` stores confirmed outcome reports and recomputes reputation aggregates. `TpoBatchProcessingService.java` processes TPO CSV batches asynchronously. `TpoWeeklyDigestService.java` sends optional weekly TPO email digests. `WhatsAppCloudApiService.java` downloads WhatsApp media and sends WhatsApp replies. `FileStorageService.java` stores uploaded files and reads process output. `CurrentUserService.java` resolves the currently authenticated user. `TpoAccessService.java` validates TPO user and institution access.

### Models

`User.java` represents application users and roles. `Prediction.java` represents saved job analysis results. `Institution.java` represents colleges or verified organizations. `BatchJob.java` represents asynchronous TPO CSV jobs. `OutcomeReport.java` represents user-submitted confirmed scam or legitimate outcomes. `CompanyReputationAggregate.java` stores approved crowdsourced reputation totals.

### Repositories

`UserRepository.java`, `PredictionRepository.java`, `InstitutionRepository.java`, `BatchJobRepository.java`, `OutcomeReportRepository.java`, and `CompanyReputationAggregateRepository.java` provide database operations for their corresponding entities.

### DTOs

`DetectionResult.java` carries the full output of the detection pipeline. `Entities.java` stores extracted phones, emails, persons, and organizations. `CompanyInfo.java` stores detected company, website, and LinkedIn URL. `GroqVerificationResult.java` stores Groq AI verification output. `ReviewVerificationResult.java` stores public review reputation output. `ReviewEvidenceDebug.java` stores raw review evidence debugging details. `ForensicsResult.java` stores document forensics score and flags. `RedFlagCheck.java` is the unified evidence item used across the system. `ScamReport.java` stores external scam or review source references. `OutcomeReputationWarning.java` stores community warning data. `PredictionPoint.java` stores chart data.

### Security

`AppUserDetails.java` adapts the local `User` entity to Spring Security. `CustomUserDetailsService.java` loads users for password login. `OAuth2LoginSuccessHandler.java` creates or links users after Google login. `OAuth2UserInfo.java` extracts email and Google ID from OAuth attributes.

### Frontend Templates

`login.html` displays the login page. `signup.html` displays normal and TPO signup. `dashboard.html` displays normal user analysis input and prediction history. `result.html` displays full risk analysis results. `admin-outcome-reports.html` displays admin moderation. `tpo-dashboard.html` displays the placement-cell dashboard. `base.html` is a shared layout file kept for reusable template structure. `style.css` contains application styling.

### Scripts and Models

`scripts/predict_score.py` loads the trained ML model and vectorizer, receives job text through standard input, and prints a fraud probability score. `models/fake_job_model.pkl` stores the trained fake-job classifier. `models/tfidf_vectorizer.pkl` stores the TF-IDF vectorizer used to convert text into model input features.

### Deployment Files

`Dockerfile` builds the Render deployment image with Java, Maven build output, Python, Tesseract OCR, scripts, and ML models. `requirements.txt` documents Python ML dependencies, though the Dockerfile currently installs system packages for stability. `.gitignore` excludes secrets, build output, uploads, logs, and allows required model files for deployment. `application.properties` maps Spring configuration to environment variables.

## Deployment Overview

The project is deployed as a Docker-based Render web service. Render provides the runtime `PORT`, and the application reads it through `server.port=${PORT:8081}`. Render PostgreSQL provides `DATABASE_URL`. The Dockerfile installs Tesseract and Python dependencies so OCR and ML scoring can run inside the deployed service. Google OAuth redirect URIs must include the Render app URL. Optional integrations such as WhatsApp, WhoisXML, and SMTP can be enabled by adding their environment variables.

## Testing Summary

The project has been verified with Maven:

```text
mvn test
BUILD SUCCESS
```

Manual testing should still be performed for external integrations because they depend on live credentials and third-party dashboards. Google OAuth, Gemini, Groq, Serper, WhoisXML, WhatsApp Cloud API, SMTP, and Render PostgreSQL should be tested using the steps in `docs/TESTING_GUIDE.md`.

## Limitations

The system depends on third-party API availability and API keys. OCR accuracy depends on image quality and Tesseract output. Review scraping may fall back to snippets when pages block scraping or require login. AI verification results should be treated as decision support rather than legal proof. Uploaded files on Render should use persistent storage if long-term file retention is required. The ML model quality depends on the dataset and should be retrained with newer data for production use.

## Future Enhancements

Future improvements can include automated unit and integration tests, a React frontend, advanced NLP entity recognition, production-grade object storage for uploads, stronger document image forensics, email verification for TPO signup, role-management UI for admins, richer analytics for institutions, and better monitoring of API failures and fallback behavior.

## Conclusion

The Fake Job Post System is a complete multi-signal job fraud detection platform. It combines machine learning, OCR, PDF processing, AI verification, public reputation search, document forensics, evidence trails, community reports, admin moderation, WhatsApp intake, and college placement-cell bulk screening. The project is useful for students, job seekers, and institutions that want to reduce exposure to fake job postings and fraudulent offer letters.

