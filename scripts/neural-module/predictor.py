#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import csv
import io
import json
import logging
import os
import re
import threading
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Tuple, Set
import torch
from transformers import LogitsProcessor

# -------------------------
# Logging setup
# -------------------------
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

# -------------------------
# JSON loader (orjson optional)
# -------------------------
try:
    import orjson

    _HAS_ORJSON = True
except Exception:
    _HAS_ORJSON = False


def _json_loads(s: str) -> Any:
    if _HAS_ORJSON:
        return orjson.loads(s)
    return json.loads(s)


# -------------------------
# File loading helpers
# -------------------------
def _read_text(path: str) -> str:
    with io.open(path, "r", encoding="utf-8") as f:
        return f.read()


def _load_messages_from_json(path: str) -> List[Dict[str, str]]:
    """Load chat-style messages from JSON."""
    obj = _json_loads(_read_text(path))
    if isinstance(obj, dict) and "messages" in obj:
        return obj["messages"]
    if isinstance(obj, list):
        return obj
    raise ValueError(f"Unsupported JSON format in {path}")


def _load_messages_from_jsonl(path: str) -> List[Dict[str, str]]:
    """Load messages from JSONL format."""
    out: List[Dict[str, str]] = []
    with io.open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            rec = _json_loads(line)
            if isinstance(rec, dict) and "messages" in rec:
                out.extend(rec["messages"])
            elif isinstance(rec, dict) and all(k in rec for k in ("role", "content")):
                out.append(rec)
            else:
                out.append({"role": "user", "content": json.dumps(rec, ensure_ascii=False)})
    return out


def _load_messages_from_csv(path: str) -> List[Dict[str, str]]:
    """Load messages from CSV format."""
    out: List[Dict[str, str]] = []
    with io.open(path, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            role = (row.get("role") or "user").strip().lower()
            content = row.get("content") or ""
            out.append({"role": role, "content": content})
    return out


def _load_tokenized_sample(path: str) -> Dict[str, Any]:
    """
    Load a tokenized sample from the training format JSON.
    Format: {"metadata": {...}, "samples": [{"input_ids": [...], "labels": [...], "constraints": [...]}]}
    """
    obj = _json_loads(_read_text(path))

    if not isinstance(obj, dict):
        raise ValueError(f"Expected dict, got {type(obj)}")

    if "samples" not in obj:
        raise ValueError(f"Missing 'samples' field in {path}")

    samples = obj["samples"]
    if not samples or len(samples) == 0:
        raise ValueError(f"No samples found in {path}")

    # Return first sample
    sample = samples[0]

    # Validate structure
    required_fields = ["input_ids", "labels", "constraints"]
    for field in required_fields:
        if field not in sample:
            raise ValueError(f"Missing '{field}' in sample")

    logger.info(f"Loaded tokenized sample: length={sample.get('length', len(sample['input_ids']))}, "
                f"constraints={len(sample['constraints'])}")

    return sample


def load_messages_from_file(path: str) -> List[Dict[str, str]]:
    """
    Load messages from various formats.
    Supports:
    - JSON with "messages" field (chat format)
    - JSON with "samples" field (tokenized training format) -> returns empty list
    - JSONL
    - CSV
    - Plain text
    """
    ext = os.path.splitext(path)[1].lower()

    # Try to detect format by content
    if ext in (".json",):
        text = _read_text(path)
        obj = _json_loads(text)

        # Check if it's tokenized format
        if isinstance(obj, dict) and "samples" in obj:
            # This is a tokenized sample - signal different handling
            return []

        # Otherwise, standard chat format
        return _load_messages_from_json(path)

    if ext in (".jsonl",):
        return _load_messages_from_jsonl(path)
    if ext in (".csv",):
        return _load_messages_from_csv(path)

    # Plain text
    txt = _read_text(path)
    return [{"role": "user", "content": txt}]


# -------------------------
# Trie for Constrained Decoding
# -------------------------
class TrieNode:
    def __init__(self):
        self.children: Dict[int, 'TrieNode'] = {}
        self.is_terminal = False


class ConstraintTrie:
    def __init__(self, constraint_sequences: List[List[int]], eos_token_id: Optional[int] = None):
        self.root = TrieNode()
        self.eos_token_id = eos_token_id
        self._build_trie(constraint_sequences)

    def _build_trie(self, sequences: List[List[int]]):
        for seq in sequences:
            if not seq:
                continue
            current = self.root
            for token_id in seq:
                if token_id not in current.children:
                    current.children[token_id] = TrieNode()
                current = current.children[token_id]
            current.is_terminal = True

    def get_valid_continuations(self, prefix: List[int]) -> Set[int]:
        current = self.root
        for token_id in prefix:
            if token_id not in current.children:
                return set()
            current = current.children[token_id]

        valid_tokens = set(current.children.keys())
        if current.is_terminal and self.eos_token_id is not None:
            valid_tokens.add(self.eos_token_id)
        return valid_tokens


# -------------------------
# Constrained Logits Processor
# -------------------------
class InferenceConstrainedLogitsProcessor(LogitsProcessor):
    """
    Applies constraints during inference token-by-token.
    """

    def __init__(self, tokenizer, constraint_trie: Optional[ConstraintTrie],
                 prompt_length: int, stage: str = "selection"):
        self.processing_class = tokenizer
        self.constraint_trie = constraint_trie
        self.prompt_length = prompt_length
        self.stage = stage
        self.call_count = 0

    def __call__(self, input_ids: torch.Tensor, scores: torch.Tensor) -> torch.Tensor:
        self.call_count += 1

        # If in generation stage (literal), don't apply constraints
        if self.stage == "generation" or self.constraint_trie is None:
            return scores

        # Extract generated tokens so far (after prompt)
        generated_tokens = input_ids[0, self.prompt_length:].tolist()

        # Get valid tokens from Trie
        valid_tokens = self.constraint_trie.get_valid_continuations(generated_tokens)

        if not valid_tokens:
            # No valid constraints, leave model free
            return scores

        # Mask: -inf for invalid tokens, 0 for valid tokens
        constraint_mask = torch.full_like(scores, float('-inf'))
        for token_id in valid_tokens:
            if 0 <= token_id < scores.shape[-1]:
                constraint_mask[:, token_id] = 0.0

        return scores + constraint_mask


# -------------------------
# JSON Output Parsing
# -------------------------
def _extract_json_choice(text: str) -> Optional[str]:
    """
    Extract the 'choice' value from generated JSON.
    """
    try:
        # Pattern match {"choice": "..."}
        match = re.search(r'\{\s*"choice"\s*:\s*"([^"]*)"\s*\}', text)
        if match:
            return match.group(1)

        # Fallback: direct JSON parsing
        parsed = json.loads(text)
        if isinstance(parsed, dict) and "choice" in parsed:
            return parsed["choice"]
    except:
        pass
    return None


# -------------------------
# Generation from Tokenized Sample
# -------------------------
def _generate_from_tokenized(
        model,
        tok,
        sample: Dict[str, Any],
        max_tgt_length: int,
        do_sample: bool = False,
        temperature: float = 0.7,
        top_p: float = 0.9,
) -> Tuple[List[int], str]:
    """
    Generate from a pre-tokenized sample.
    Uses input_ids directly and applies constraints if available.
    """
    input_ids = sample["input_ids"]
    constraints = sample.get("constraints", [])

    device = model.device
    input_tensor = torch.tensor([input_ids], dtype=torch.long, device=device)
    attention_mask = torch.ones_like(input_tensor)

    # Build constraint trie if constraints exist
    constraint_trie = None
    if constraints:
        try:
            constraint_trie = ConstraintTrie(constraints, eos_token_id=tok.eos_token_id)
            logger.info(f"Built constraint trie with {len(constraints)} sequences")
        except Exception as e:
            logger.warning(f"Failed to build constraint trie: {e}")

    # Build logits processor
    logits_processor = None
    if constraint_trie:
        logits_processor = InferenceConstrainedLogitsProcessor(
            tokenizer=tok,
            constraint_trie=constraint_trie,
            prompt_length=len(input_ids),
            stage="selection"  # Assume selection stage for constrained generation
        )

    gen_kwargs: Dict[str, Any] = dict(
        max_new_tokens=int(max_tgt_length),
        do_sample=bool(do_sample),
        eos_token_id=tok.eos_token_id,
        pad_token_id=getattr(tok, "pad_token_id", tok.eos_token_id),
    )

    if do_sample:
        gen_kwargs.update(temperature=float(temperature), top_p=float(top_p))

    if logits_processor:
        gen_kwargs["logits_processor"] = [logits_processor]

    with torch.no_grad():
        out = model.generate(
            input_ids=input_tensor,
            attention_mask=attention_mask,
            **gen_kwargs,
        )

    new_ids = out[0, input_tensor.shape[1]:].tolist()
    generated_text = tok.decode(new_ids, skip_special_tokens=True)

    logger.info(f"Generated {len(new_ids)} tokens: {generated_text[:100]}...")

    return new_ids, generated_text


# -------------------------
# Chat-style prompt building (legacy)
# -------------------------
@dataclass
class ChatSpecialTokens:
    imstart_id: int
    imend_id: int


def build_prompt_ids_for_inference(
        tok,
        messages: List[Dict[str, str]],
        max_src_length: int,
) -> Tuple[List[int], int, Dict[str, int]]:
    """
    Build prompt from chat-style messages (legacy format).
    """
    # Get special tokens
    imstart = "<|im_start|>"
    imend = "<|im_end|>"

    imstart_ids = tok.encode(imstart, add_special_tokens=False)
    imend_ids = tok.encode(imend, add_special_tokens=False)

    if not imstart_ids or not imend_ids:
        raise ValueError("Chat special tokens not found in tokenizer")

    imstart_id = imstart_ids[0]
    imend_id = imend_ids[0]

    # Build conversation
    conversation = []
    for msg in messages:
        role = msg.get("role", "user")
        content = msg.get("content", "")

        conversation.append(imstart_id)
        role_ids = tok.encode(role, add_special_tokens=False)
        conversation.extend(role_ids)
        conversation.append(tok.encode("\n", add_special_tokens=False)[0])

        content_ids = tok.encode(content, add_special_tokens=False)
        conversation.extend(content_ids)
        conversation.append(imend_id)
        conversation.append(tok.encode("\n", add_special_tokens=False)[0])

    # Truncate if needed
    if len(conversation) > max_src_length:
        conversation = conversation[-max_src_length:]

    special = {
        "imstart_id": imstart_id,
        "imend_id": imend_id,
    }

    return conversation, len(conversation), special


# -------------------------
# Main generation function
# -------------------------
def generate_once_for_file(
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
    Supports both chat-style and tokenized formats.
    """
    # Try to detect format
    ext = os.path.splitext(path)[1].lower()

    if ext == ".json":
        text = _read_text(path)
        obj = _json_loads(text)

        # Check if tokenized format
        if isinstance(obj, dict) and "samples" in obj:
            logger.info("Detected tokenized format")
            sample = _load_tokenized_sample(path)
            new_ids, _ = _generate_from_tokenized(
                model, tok, sample, max_tgt_length, do_sample, temperature, top_p
            )
            return new_ids

    # Otherwise, use standard chat-based generation
    logger.info("Detected chat-style format")
    messages = load_messages_from_file(path)

    if not messages:
        raise ValueError(f"No messages loaded from {path}")

    prompt_ids, ctx_len, special = build_prompt_ids_for_inference(
        tok, messages, max_src_length=max_src_length
    )

    device = model.device
    input_ids = torch.tensor([prompt_ids], dtype=torch.long, device=device)
    attention_mask = torch.ones_like(input_ids)

    gen_kwargs: Dict[str, Any] = dict(
        max_new_tokens=int(max_tgt_length),
        do_sample=bool(do_sample),
        eos_token_id=special["imend_id"],  # stop on <|im_end|>
        pad_token_id=getattr(tok, "pad_token_id", special["imend_id"]),
    )

    if do_sample:
        gen_kwargs.update(temperature=float(temperature), top_p=float(top_p))

    with torch.no_grad():
        out = model.generate(
            input_ids=input_ids,
            attention_mask=attention_mask,
            **gen_kwargs,
        )

    new_ids = out[0, input_ids.shape[1]:].tolist()
    return new_ids


# -------------------------
# Public API
# -------------------------
class TokenNotFoundException(Exception):
    def __init__(self, predicted_token: str = ""):
        self.predicted_token = predicted_token
        super().__init__(predicted_token)


def predict_next_token(
        device: torch.device,
        filename: str,
        model,
        tokenizer,
        max_src_length: int,
        max_tgt_length: int,
) -> str:
    """
    Generate the next token/oracle with constraints applied.
    Supports both tokenized and chat-style formats.
    """
    path = os.path.abspath(filename)
    if not os.path.isfile(path):
        raise TokenNotFoundException(predicted_token=f"[missing file: {path}]")

    logger.info(f"Predicting next token from: {path}")

    # Generate
    new_ids = generate_once_for_file(
        model=model,
        tok=tokenizer,
        path=path,
        max_src_length=max_src_length,
        max_tgt_length=max_tgt_length,
        do_sample=False,
        temperature=0.7,
        top_p=0.9,
    )

    # Decode
    generated_text = tokenizer.decode(new_ids, skip_special_tokens=True)
    # Try to extract JSON choice
    choice = _extract_json_choice(generated_text)
    if choice is None:
        # Fallback: return raw text
        choice = generated_text.strip()
    if not choice:
        raise TokenNotFoundException(predicted_token="")
    logger.info(f"Predicted choice: {choice}")
    return choice
