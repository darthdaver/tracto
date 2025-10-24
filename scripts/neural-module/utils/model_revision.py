import os, json
from pathlib import Path

repo = "Qwen/Qwen2.5-Coder-1.5B"
cache = Path.home()/".cache"/"huggingface"/"hub"/f"models--{repo.replace('/','--')}"
print("cache dir:", cache)
refs_main = cache/"refs"/"main"
if refs_main.exists():
    print("last fetched main:", refs_main.read_text().strip())

for snap in sorted((cache/"snapshots").glob("*")):
    tj = snap/"tokenizer.json"
    if tj.exists():
        try:
            data = json.load(open(tj))
            vocab = len(data["model"]["vocab"])
            print(snap.name, "vocab_size=", vocab)
        except Exception:
            pass
