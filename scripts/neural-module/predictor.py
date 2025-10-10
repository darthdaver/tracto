#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import csv
import io
import json
import os
import threading
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Tuple

import torch

try:
    import orjson
    _HAS_ORJSON = True
except Exception:
    _HAS_ORJSON = False


# -------------------------
# Exceptions (server imports this)
# -------------------------

class TokenNotFoundException(Exception):
    def __init__(self, predicted_token: str = ""):
        self.predicted_token = predicted_token
        super().__init__(f"Token not found: {predicted_token}")


# -------------------------
# Minimal session state (per input file)
# -------------------------

@dataclass
class _Session:
    filename: str
    mtime: float
    generated_ids: List[int]
    cursor: int
    # optional cached decoded text so we can compute diffs (not strictly needed here)
    # decoded_so_far: str = ""


_SESSIONS: Dict[str, _Session] = {}
_LOCK = threading.Lock()


# -------------------------
# File loading helpers
# -------------------------

_DEFAULT_SYSTEM = (
    "You are a testing developer assistant. You must help to automatize the process "
    "of generating test oracles, token-by-token."
)

def _read_text(path: str) -> str:
    with io.open(path, "r", encoding="utf-8", errors="replace") as f:
        return f.read()

def _json_loads(s: str) -> Any:
    if _HAS_ORJSON:
        return orjson.loads(s)
    return json.loads(s)

def _load_messages_from_jsonlike(obj: Any) -> List[Dict[str, str]]:
    """
    Normalize a variety of JSON/JSONL shapes into [{"role":..., "content":...}, ...].
    """
    if isinstance(obj, dict):
        if "messages" in obj and isinstance(obj["messages"], list):
            return obj["messages"]
        # Common alternatives
        if "prompt" in obj and isinstance(obj["prompt"], str):
            return [{"role": "user", "content": obj["prompt"]}]
        if "input" in obj and isinstance(obj["input"], str):
            return [{"role": "user", "content": obj["input"]}]
        if "text" in obj and isinstance(obj["text"], str):
            return [{"role": "user", "content": obj["text"]}]
        # Fallback: stringify the dict
        return [{"role": "user", "content": json.dumps(obj, ensure_ascii=False)}]

    if isinstance(obj, list):
        # Maybe already a list of role/content dicts
        if obj and isinstance(obj[0], dict) and "content" in obj[0]:
            return obj  # assume valid
        # Or a list of strings -> join
        joined = "\n".join(str(x) for x in obj)
        return [{"role": "user", "content": joined}]

    # Plain string
    if isinstance(obj, str):
        return [{"role": "user", "content": obj}]

    # Last resort
    return [{"role": "user", "content": str(obj)}]

def _load_messages_from_json(path: str) -> List[Dict[str, str]]:
    data = _json_loads(_read_text(path))
    return _load_messages_from_jsonlike(data)

def _load_messages_from_jsonl(path: str) -> List[Dict[str, str]]:
    # Take the first line that parses
    with io.open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                obj = _json_loads(line)
                return _load_messages_from_jsonlike(obj)
            except Exception:
                continue
    raise RuntimeError(f"No valid JSON object found in {path}")

def _load_messages_from_csv(path: str) -> List[Dict[str, str]]:
    """
    Heuristics:
      - If columns include 'messages', treat it as JSON.
      - Else if columns include ('role','content'), build chat from rows.
      - Else: join non-empty columns of the first row into one user prompt.
    """
    with io.open(path, "r", encoding="utf-8", errors="replace", newline="") as f:
        rdr = csv.DictReader(f)
        rows = list(rdr)
    if not rows:
        return [{"role": "user", "content": ""}]

    # 1) messages column (json)
    if "messages" in rdr.fieldnames:
        raw = rows[0].get("messages", "")
        try:
            return _load_messages_from_jsonlike(_json_loads(raw))
        except Exception:
            pass

    # 2) role/content columns
    if "role" in rdr.fieldnames and "content" in rdr.fieldnames:
        msgs = []
        for r in rows:
            role = (r.get("role") or "user").strip().lower()
            content = r.get("content") or ""
            msgs.append({"role": role, "content": content})
        return msgs

    # 3) fallback: join first row
    parts = []
    for k in rdr.fieldnames or []:
        v = rows[0].get(k)
        if isinstance(v, str) and v.strip():
            parts.append(f"{k}: {v}")
    joined = "\n".join(parts) if parts else _read_text(path)
    return [{"role": "user", "content": joined}]

def load_messages_from_file(path: str) -> List[Dict[str, str]]:
    ext = os.path.splitext(path)[1].lower()
    if ext in (".json",):
        return _load_messages_from_json(path)
    if ext in (".jsonl",):
        return _load_messages_from_jsonl(path)
    if ext in (".csv",):
        return _load_messages_from_csv(path)
    # Plain text or unknown -> whole file as a single user message
    txt = _read_text(path)
    return [{"role": "user", "content": txt}]


# -------------------------
# Chat -> token ids (matches your training format)
# -------------------------

def _role_token_ids(tok, role: str) -> List[int]:
    # "assistant\n", "user\n", "system\n"
    nl = tok("\n", add_special_tokens=False).input_ids[-1:]
    base = tok(role, add_special_tokens=False).input_ids
    return base + nl

def build_prompt_ids_for_inference(
    tok,
    messages: List[Dict[str, str]],
    max_src_length: int,
    default_system: str = _DEFAULT_SYSTEM,
) -> Tuple[List[int], int, Dict[str, int]]:
    """
    Returns:
      ids: full prompt token ids (ending with assistant header so model can generate)
      context_len: length of input ids (prompt) used for generate() slicing
      special: dict with im_start_id, im_end_id
    """
    im_start_id = tok("<|im_start|>").input_ids[0]
    im_end_id   = tok("<|im_end|>").input_ids[0]
    _sys = _role_token_ids(tok, "system")
    _usr = _role_token_ids(tok, "user")
    _asst = _role_token_ids(tok, "assistant")
    nl = tok("\n", add_special_tokens=False).input_ids[-1:]

    ids: List[int] = []

    # System
    sys_msg = default_system
    if messages and messages[0].get("role") == "system" and messages[0].get("content"):
        sys_msg = str(messages[0]["content"])
    sys_ids = [im_start_id] + _sys + tok(sys_msg, add_special_tokens=False).input_ids + [im_end_id] + nl
    ids += sys_ids

    # Turns (echo existing dialogue)
    turns = messages[1:] if messages and messages[0].get("role") == "system" else messages
    last_role = "system"
    for s in turns:
        role = s.get("role", "user").lower()
        if role not in ("user", "assistant", "system"):
            role = "user"
        role_tok = {"user": _usr, "assistant": _asst, "system": _sys}[role]
        content_ids = tok(s.get("content", ""), add_special_tokens=False).input_ids
        full = [im_start_id] + role_tok + content_ids + [im_end_id] + nl
        ids += full
        last_role = role

    # Assistant header to let model continue the conversation
    ids += [im_start_id] + _asst
    # (We do NOT add im_end here; the model should produce content then <|im_end|>)

    # Truncate from the left if too long (keep the tail, since it contains latest turns)
    if len(ids) > max_src_length:
        ids = ids[-max_src_length:]

    context_len = len(ids)
    return ids, context_len, {"im_start_id": im_start_id, "im_end_id": im_end_id}


# -------------------------
# Generation & token streaming
# -------------------------

def _generate_once_for_file(
    model,
    tok,
    path: str,
    max_src_length: int,
    max_tgt_length: int,
    do_sample: bool = False,
    temperature: float = 0.7,
    top_p: float = 0.9,
) -> List[int]:
    """
    Build the prompt, run model.generate(), and return ONLY the newly generated token ids.
    """
    messages = load_messages_from_file(path)
    prompt_ids, ctx_len, special = build_prompt_ids_for_inference(tok, messages, max_src_length=max_src_length)

    device = next(model.parameters()).device
    input_ids = torch.tensor(prompt_ids, dtype=torch.long, device=device).unsqueeze(0)
    attention_mask = torch.ones_like(input_ids, dtype=torch.bool, device=device)

    eos_ids = [special["im_end_id"]]
    if tok.eos_token_id is not None:
        # Some tokenizers have a global EOS; treat either as stop.
        if isinstance(tok.eos_token_id, (list, tuple)):
            eos_ids.extend([int(x) for x in tok.eos_token_id])
        else:
            eos_ids.append(int(tok.eos_token_id))
    # De-duplicate
    eos_ids = sorted(set(int(x) for x in eos_ids))

    gen_kwargs = dict(
        max_new_tokens=int(max_tgt_length),
        eos_token_id=eos_ids if len(eos_ids) > 1 else eos_ids[0],
        pad_token_id=tok.pad_token_id,
        do_sample=bool(do_sample),
        use_cache=True,
    )
    if do_sample:
        gen_kwargs.update(temperature=float(temperature), top_p=float(top_p))

    with torch.no_grad():
        out = model.generate(
            input_ids=input_ids,
            attention_mask=attention_mask,
            **gen_kwargs,
        )

    # Slice out the newly generated tokens
    new_ids = out[0, input_ids.shape[1]:].tolist()
    return new_ids

def _pretty_piece(tok, token_id: int, at_start: bool) -> str:
    """
    Convert a single token id to a human-friendly piece of text.
    Handles common SentencePiece '▁' space marker; leaves other tokens as-is.
    """
    piece = tok.decode([int(token_id)], skip_special_tokens=False)
    if not piece:
        return ""

    # Remove special tokens completely
    specials = set()
    try:
        specials.add(tok("<|im_start|>").input_ids[0])
        specials.add(tok("<|im_end|>").input_ids[0])
    except Exception:
        pass
    if tok.pad_token_id is not None:
        specials.add(int(tok.pad_token_id))
    if tok.eos_token_id is not None:
        if isinstance(tok.eos_token_id, (list, tuple)):
            specials.update(int(x) for x in tok.eos_token_id)
        else:
            specials.add(int(tok.eos_token_id))
    if int(token_id) in specials:
        return ""

    # SentencePiece space handling
    if "▁" in piece:
        piece = piece.replace("▁", "" if at_start else " ")

    return piece

def predict_next_token(
    device: torch.device,
    filename: str,
    model,
    tokenizer,
    max_src_length: int,
    max_tgt_length: int,
) -> str:
    """
    Entry point called by server.py. Returns the next generated token (as text)
    for the given file. Generates once and streams token-by-token over repeated calls.
    """
    path = os.path.abspath(filename)
    if not os.path.isfile(path):
        raise TokenNotFoundException(predicted_token=f"[missing file: {path}]")

    mtime = os.path.getmtime(path)

    with _LOCK:
        sess = _SESSIONS.get(path)
        # (Re)build session if new or file changed on disk
        if sess is None or abs(sess.mtime - mtime) > 1e-6:
            new_ids = _generate_once_for_file(
                model=model,
                tok=tokenizer,
                path=path,
                max_src_length=max_src_length,
                max_tgt_length=max_tgt_length,
                do_sample=False,      # deterministic by default; flip to True if you want sampling
                temperature=0.7,
                top_p=0.9,
            )
            sess = _Session(filename=path, mtime=mtime, generated_ids=new_ids, cursor=0)
            _SESSIONS[path] = sess

        # Out of tokens? signal end.
        if sess.cursor >= len(sess.generated_ids):
            raise TokenNotFoundException(predicted_token="")

        # Serve one token (as a pretty-printed piece)
        token_id = sess.generated_ids[sess.cursor]
        at_start = (sess.cursor == 0)
        piece = _pretty_piece(tokenizer, token_id, at_start=at_start)
        sess.cursor += 1
        return piece
