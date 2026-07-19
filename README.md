# Fake Job Post System

Spring Boot version of the fake job detection project.

## Stack

- Java 17
- Spring Boot 3
- Spring MVC
- Thymeleaf
- Spring Security
- Spring Data JPA
- PostgreSQL

## Project Structure

- `src/main/java/com/fakejobpostsystem` - Java source
- `src/main/resources/templates` - Thymeleaf templates
- `src/main/resources/static` - CSS and static assets
- `scripts/predict_score.py` - Python bridge for the existing ML model
- `models/` - existing `.pkl` model files used by the Python bridge

## Requirements

- Java 17 installed
- Maven installed
- Python installed if you want to use the existing ML `.pkl` model scoring
- Tesseract OCR installed if you want image text extraction

## Configuration

Main settings are in `src/main/resources/application.properties`, with secrets loaded from `.env`.

Important properties:

- `DATABASE_URL=postgresql://user:password@localhost:5432/database_name`
- `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET`
- `GEMINI_API_KEY`, `GROQ_API_KEY`, and `SERPER_API_KEY`
- `APP_PYTHON_COMMAND=python`
- `APP_TESSERACT_COMMAND=C:/Program Files/Tesseract-OCR/tesseract.exe`

You can change these paths to match your machine.

## Run The App

From the project root:

```powershell
mvn spring-boot:run
```

Then open:

```text
http://localhost:8081
```

## Build

```powershell
mvn clean package
```

The jar will be created in `target/`.

## Notes

- The old Flask project files were removed.
- The application can still use the old trained model through `scripts/predict_score.py`.
- If Python dependencies for that script are missing, the Java service falls back to a basic heuristic score.
- If Tesseract is not available, image OCR will return empty text.
- Google OAuth is configured through Spring Security OAuth2.

## Next Improvements

- Add Maven Wrapper (`mvnw`, `mvnw.cmd`)
- Add tests for controllers and detection services
