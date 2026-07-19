import sys
from pathlib import Path

try:
    import joblib
except Exception:
    raise SystemExit(1)

ROOT = Path(__file__).resolve().parents[1]
MODEL_PATH = ROOT / "models" / "fake_job_model.pkl"
VECTORIZER_PATH = ROOT / "models" / "tfidf_vectorizer.pkl"

text = sys.stdin.read().strip()
if not text:
    print("0.0")
    raise SystemExit(0)

try:
    model = joblib.load(MODEL_PATH)
    vectorizer = joblib.load(VECTORIZER_PATH)
    vector = vectorizer.transform([text])
    score = float(model.predict_proba(vector)[0][1])
    print(f"{score:.6f}")
except Exception:
    raise SystemExit(1)
