#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Balanced, pre-tokenized SFT corpus using existing size bins (Small/Medium/Large).

- Uses train/val CSVs with columns: project_id,num_oracles,size_bin
- Allocates token budgets per bin and per project (√-scaling)
- Encodes chat with Qwen tokenizer (+ <|im_start|>, <|im_end|>) OR plain src/tgt pairs (no chat template)
- Assistant-only supervision (IGNORE_INDEX=-100)
- Length buckets by *token length after encoding*: short/med/long
- Sharded memmap output: {split}.{shard}.{input_ids|labels}.bin, .idx, {split}.meta.json

Example:
  python build_tokenized_dataset_bins.py \
    --model_name_or_path Qwen/Qwen2.5-Coder-14B-Instruct \
    --data_dir   /path/jsonl \
    --train_csv  /path/splits/train_projects.csv \
    --val_csv    /path/splits/val_projects.csv \
    --out_dir    /path/tokenized \
    --train_token_budget 2000000000 \
    --val_token_budget   40000000 \
    --max_length 4096 \
    --bin_allocation sqrt \
    --alpha 0.5 \
    --target_mix 0.4,0.4,0.2 \
    --tokens_per_shard 50000000 \
    --train_drop_long
"""

import os, io, csv, glob, json, math, random, argparse
from typing import Dict, List, Tuple, Iterable, Optional, Iterator, Union, Any
import numpy as np
import pandas as pd
import orjson
from transformers import AutoTokenizer, AutoModel
from huggingface_hub import snapshot_download
from pathlib import Path

# --------------------------
# Global Variables and types
# --------------------------
# Standard label mask value (PyTorch cross entropy ignores -100)
IGNORE_INDEX = -100
Sample = Union[List[Dict[str, Any]], Tuple[str, str]]

# -----------------
# Auxiliary classes
# -----------------

class ShardWriter:
    def __init__(self, out_dir: str, split: str, tokens_per_shard: int):
        os.makedirs(out_dir, exist_ok=True)
        self.out_dir = out_dir
        self.split = split
        self.tokens_per_shard = tokens_per_shard
        self.shard_id = 0
        self._open_new()

    def _open_new(self):
        base = os.path.join(self.out_dir, f"{self.split}.{self.shard_id:05d}")
        self.ids_path = base + ".input_ids.bin"
        self.lbl_path = base + ".labels.bin"
        self.idx_path = base + ".idx"
        self.ids_f = open(self.ids_path, "wb")
        self.lbl_f = open(self.lbl_path, "wb")
        self.idx = [0]
        self.tok_count = 0
        self.n_samples = 0

    def _roll(self, add_tokens: int):
        if self.tok_count + add_tokens > self.tokens_per_shard and self.n_samples > 0:
            self.close()
            self.shard_id += 1
            self._open_new()

    def write(self, input_ids: np.ndarray, labels: np.ndarray):
        L = int(input_ids.shape[0])
        self._roll(L)
        self.ids_f.write(input_ids.tobytes()); self.lbl_f.write(labels.tobytes())
        self.tok_count += L; self.n_samples += 1; self.idx.append(self.tok_count)

    def close(self):
        self.ids_f.flush(); self.ids_f.close()
        self.lbl_f.flush(); self.lbl_f.close()
        with open(self.idx_path, "wb") as f:
            np.asarray(self.idx, np.uint64).tofile(f)

class EncoderInterface:
    def encode(self, sample: Sample) -> Tuple[Optional[np.ndarray], Optional[np.ndarray]]:
        """
        Encodes a (src,tgt) pair or a conversation (list of messages exchanged between the user and the assistant).
        """
        pass


class ChatEncoder(EncoderInterface):
    def __init__(
            self,
            tok: AutoTokenizer,
            max_len: int,
            only_last_turn_loss: bool =True,
            drop_long: bool =False,
            assistant_roles: Tuple[str, ...] = ("assistant",),
            user_roles: Tuple[str, ...] = ("user",),
            system_roles: Tuple[str, ...] = ("system",),
    ):
        # Set tokenizer
        self.tok = tok
        # Set prompt max len
        self.max_len = max_len
        # Set if only the last assistant message is used for training (True) or
        # the full history of assistant messages (False)
        self.only_last = only_last_turn_loss
        # If set to `True` the tokenizer completely skip the sample if it exceeds
        # the `max_len`. If set to `False` the input is truncated
        self.drop_long = drop_long
        # Assign the values that refers to the assistant, the user and the system
        # The usage of tuples and set relax to the possibility that multiple identifiers
        # can refer to the same role
        self.assistant_roles = set(assistant_roles)
        self.user_roles = set(user_roles)
        self.system_roles = set(system_roles)
        # Boolean attribute to check if the tokenizer has the apply_chat_template api
        # to format messages before tokenize them
        self._has_template = hasattr(self.tok, "apply_chat_template")

    def encode(self, sample: Sample) -> Tuple[Optional[np.ndarray], Optional[np.ndarray]]:
        """
        Encodes a conversation (list of messages exchanged between the user and the assistant).
        If only_last_turn_loss=True, the script unmasks only the final assistant message's content; earlier turns are
        context only. Otherwise, the script unmask all the messages' content provided by the assistant.
        Messages format:
            messages: [{"role": "user"|"assistant"|"system", "content": "..."}, "..."]
        """
        # No messages mean nothing to encode
        if not sample:
            return None, None
        # Get the index of the last message sent by the assistant in the conversation
        last_asst_idx = self._find_last_assistant_index(sample)
        # Check if the conversation contains a message from the assistant
        if last_asst_idx is None:
            # No assistant turn to supervise -> skip this conversation sample
            return None, None
        if self.only_last:
            return self._encode_last_turn(sample, last_asst_idx)
        else:
            # Optional: multi-turn supervision (each assistant turn contributes)
            # For compactness, we implement last-turn fully and provide a
            # straightforward multi-turn variant below.
            return self._encode_multi_turn(sample)

    def _encode_last_turn(self, msgs: List[Dict[str, str]], last_asst_idx: int):
        """
        Encode the conversation to input/labels so that ONLY the last assistant message contributes to loss
        during the training process.
        The method does this by:
          1) Formatting the prefix WITHOUT the last assistant message,
             but WITH an 'assistant header' via add_generation_prompt=True.
          2) Tokenizing the last assistant content alone (no specials).
          3) Formatting the full conversation to discover any tail tokens (EOS, EOT, etc.)
             and taking the remainder after prefix+content as tail_ids (masked).
        """
        # Split messages into prefix (context) and last assistant
        prefix_msgs = msgs[:last_asst_idx] + self._msg_without_content(msgs[last_asst_idx])
        asst_text = msgs[last_asst_idx]["content"] or ""
        # Tokenize the part of the conversation that preceded the final assistant message
        # (used in the training phase to compute the loss and update the weights accordingly)
        ctx_ids = self._format_ids(prefix_msgs, add_generation_prompt=True)
        # Tokenize the final assistant message that contribute to loss during the training.
        # `apply_chat_template` produces wrappers around the assistant span (assistant header, separators, maybe BOS/EOT).
        # Therefore, the answer is encoded using the _tokenize_plain so that the labels will include only the content
        # tokens (no headers, no closing specials).
        ans_ids = self._tokenize_plain(asst_text)
        # The whole conversation (including the last message of the assistant) is initially considered
        # as part of the input prompt (all the idxs in the labels are ignored)
        # This it the entire conversation as seen it in the wild, using the model’s chat template
        # (no “generation prompt” cut-off). This typically equals ctx_ids + ans_ids + trailer,
        # where trailer is whatever the template appends after the assistant text
        # (often an end-of-turn / EOS token, maybe a newline).
        full_ids = self._format_ids(msgs, add_generation_prompt=False)
        # Compute tail_ids as "what remains" after ctx + ans
        # It represents the “trailer” after the answer (e.g., <eot>, </s>, a newline).
        tail_start = len(ctx_ids) + len(ans_ids)
        tail_ids = full_ids[tail_start:] if tail_start <= len(full_ids) else []
        # Assemble input and labels
        input_ids = ctx_ids + ans_ids + tail_ids
        labels = [IGNORE_INDEX] * len(ctx_ids) + ans_ids + [IGNORE_INDEX] * len(tail_ids)
        # Length policy: drop vs truncate
        if len(input_ids) > self.max_len:
            # Discard the conversation sample if `drop_long` policy is set to `True`
            if self.drop_long:
                return None, None
            # Truncate the input and labels to the max_len (this implementation implies that
            # both the input and the labels has the same max_len - this is an assumption that
            # does not always hold)
            else:
                input_ids = input_ids[: self.max_len]
                labels = labels[: self.max_len]
        # Return the tokenized conversation as a tuple of numpy arrays (input,label)
        return np.asarray(input_ids, dtype=np.int32), np.asarray(labels, dtype=np.int32)

    def _encode_multi_turn(self, msgs: List[Dict[str, str]]):
        """
        Generic (optional) multi-turn supervision:
        every assistant message contributes to loss at its content span.
        We compute full_ids once, then for each assistant turn we compute
        the context length up to that turn using add_generation_prompt=True
        and unmask exactly the span for that assistant content.
        """
        # The whole conversation (including the last message of the assistant) is initially considered
        # as part of the input prompt (all the idxs in the labels are ignored)
        # This it the entire conversation as seen it in the wild, using the model’s chat template
        # (no “generation prompt” cut-off). This typically equals ctx_ids + ans_ids + trailer,
        # where trailer is whatever the template appends after the assistant text
        # (often an end-of-turn / EOS token, maybe a newline).
        full_ids = self._format_ids(msgs, add_generation_prompt=False)
        labels = [IGNORE_INDEX] * len(full_ids)
        # For each assistant message j, compute where its content starts
        for j, m in enumerate(msgs):
            # If the role of the message does not refer to assistant, skip the message
            if m.get("role") not in self.assistant_roles:
                continue
            # Consider the messages before the assistant message and replace
            # the message of the assistant (target message) with an empty message
            prefix_msgs = msgs[:j] + self._msg_without_content(msgs[j])
            # Tokenize the context and the answer
            ctx_ids = self._format_ids(prefix_msgs, add_generation_prompt=True)
            # `apply_chat_template` produces wrappers around the assistant span (assistant header, separators, maybe BOS/EOT).
            # The answer is encoded using the _tokenize_plain so that the labels will include only the content
            # tokens (no headers, no closing specials).
            ans_ids = self._tokenize_plain(m.get("content", ""))
            # Get the start and end indices of the answer
            start = len(ctx_ids)
            end = start + len(ans_ids)
            # Guard against odd formatting mismatches
            if end <= len(full_ids):
                labels[start:end] = ans_ids
        # Set the input prompt as the full conversation tokenized
        input_ids = full_ids
        # Check if the input prompt exceeds the max length
        if len(input_ids) > self.max_len:
            # Discard the conversation sample if `drop_long` policy is set to `True`
            if self.drop_long:
                return None, None
            # Truncate the input and labels to the max_len (this implementation implies that
            # both the input and the labels has the same max_len - this is an assumption that
            # does not always hold)
            else:
                input_ids = input_ids[: self.max_len]
                labels = labels[: self.max_len]
        # Return the tokenized conversation as a tuple of numpy arrays (input,label)
        return np.asarray(input_ids, dtype=np.int32), np.asarray(labels, dtype=np.int32)

    def _msg_without_content(self, msg: Dict[str, str]) -> List[Dict[str, str]]:
        """
        Return a message without content (content is empty string), within a list.
        This lets `apply_chat_template` produce the assistant header when used with add_generation_prompt=True.
        """
        return [{
            "role": msg.get("role", ""),
            "content": "",
        }]

    def _format_ids(self, msgs: List[Dict[str, str]], add_generation_prompt: bool) -> List[int]:
        """
        Returns token ids for the conversation *surface form* up to (optionally) the assistant header.
        Prefers tokenizer.apply_chat_template(..., tokenize=True). If unavailable or it throws,
        falls back to a minimal plain-text formatter.
        """
        # If the model provides a tokenizer with chat template api, tokenize directly
        if self._has_template:
            try:
                ids = self.tok.apply_chat_template(
                    msgs,
                    tokenize=True,
                    add_generation_prompt=add_generation_prompt,
                    return_tensors=None,
                )
                # Some tokenizers return a list or np array; normalize to list[int]
                if hasattr(ids, "tolist"):
                    # If the condition is true, this line calls the tolist() method on ids,
                    # converting it from a NumPy array to a standard Python list.
                    ids = ids.tolist()
                # The map(int, ids) function applies the int constructor to each element in ids,
                # converting all elements to integers. The list() function then creates a list
                # from the mapped integers, which is returned by the method.
                return list(map(int, ids))
            except Exception:
                pass  # fall through to fallback
        # Fallback: minimal, generic "User/Assistant/System" formatting
        text = self._format_plain_text(msgs, add_generation_prompt=add_generation_prompt)
        return self._tokenize_plain(text)

    def _tokenize_plain(self, text: str) -> List[int]:
        """
        Tokenize raw text without adding model-level special tokens. This method should be used when the
        tokenizer corresponding to the model selected, do not implement the chat template api.
        """
        return self.tok(text, add_special_tokens=False).input_ids

    # ---------- Tiny plain-text fallback ----------

    def _format_plain_text(self, messages: List[Dict[str, str]], add_generation_prompt: bool) -> str:
        """
        A dead-simple, model-agnostic fallback surface form.
        (Good enough for datasets without a proper chat_template api included in the tokenizer associated
        to the model chosen.)
        """
        parts = []
        for m in messages:
            # Retrieve the role and capitalize it. If the role attribute is not present
            # in the message, assume it is a message from the `user`
            role = m.get("role", "user").capitalize()
            # Get the content of the message, if present
            content = (m.get("content") or "").strip()
            # Map the message to the template format
            parts.append(f"{role}:\n{content}".strip())
        # Compose the conversation joining the messages exchanged
        text = "\n\n".join(parts)
        # Append the assistant role prefix if `add_generation_prompt` is `True`
        if add_generation_prompt:
            text = (text + "\n\nAssistant:\n").lstrip()
        # Returns the conversation in the plain text template
        return text

    def _find_last_assistant_index(self, msgs: List[Dict[str, str]]) -> Optional[int]:
        """
        Given a list of messages, returns the index of the last message sent by the assistant.
        """
        for i in range(len(msgs) - 1, -1, -1):
            if msgs[i].get("role") in self.assistant_roles:
                return i
        return None


class PairEncoder(EncoderInterface):
    """
    Encoder for plain source-target pairs (no chat template).
        - input_ids  = tok(src) + tok(tgt) + [eos?]
        - labels     = IGNORE for src tokens, tok(tgt) + [eos?]
    Truncation/drop behavior mirrors ChatEncoder.
    """
    def __init__(
            self,
            tok: AutoTokenizer,
            max_len: int,
            drop_long: bool = False,
            append_eos: bool = True
    ):
        # Set tokenizer
        self.tok = tok
        # Set prompt max len
        self.max_len = max_len
        # If set to `True` the tokenizer completely skip the sample if it exceeds
        # the `max_len`. If set to `False` the input is truncated
        self.drop_long = drop_long
        # If set to `True` the tokenizer appends the EOS token at the end of the input sequence
        self.append_eos = append_eos
        # Get the id of the EOS token, if present
        self.eos_id = getattr(tok, "eos_token_id", None)

    def _tokenize_plain(self, text: str):
        if text is None or text == "":
            return []
        return self.tok.encode(text, add_special_tokens=False)

    def encode(self, sample: Sample) -> Tuple[Optional[np.ndarray], Optional[np.ndarray]]:
        src, tgt = sample
        # Encode src and tgt separately (no special tokens)
        src_ids = self._tokenize_plain(src)
        tgt_ids = self._tokenize_plain(tgt)
        tail = [self.eos_id] if (self.append_eos and self.eos_id is not None) else []
        # Assemble input and labels (eos if present)
        input_ids = src_ids + tgt_ids + tail
        # Only the target tokens contribute to loss
        labels = [IGNORE_INDEX] * len(src_ids) + tgt_ids + tail
        # Manage max length policy: drop vs truncate
        if len(input_ids) > self.max_len:
            # Discard the sample if `drop_long` policy is set to `True`
            if self.drop_long:
                return None, None
            # Truncate the input and labels to the max_len (this implementation implies that
            # both the input and the labels has the same max_len - this is an assumption that
            # does not always hold)
            input_ids = input_ids[:self.max_len]
            labels = labels[:self.max_len]
        return np.array(input_ids, dtype=np.int32), np.array(labels, dtype=np.int32)


def build_tokenizer(model_name_or_path: str, max_length: int, revision: str, special_tokens: List[str]) -> Tuple[AutoTokenizer,int,int]:
    tok = AutoTokenizer.from_pretrained(
        model_name_or_path,
        revision=revision,
        trust_remote_code=True,
        model_max_length=max_length,
        padding_side="right",
    )
    vocab_size_before = len(tok)
    if tok.pad_token_id is None:
        tok.pad_token = tok.eos_token
    if special_tokens:
        tok.add_special_tokens({"additional_special_tokens": special_tokens})
    return tok, vocab_size_before, len(tok)


def read_split_csv(
        path: str
) -> Dict[str, Dict]:
    """
    Reads the original csv file containing the list of projects to process (`project_id`), the number of oracles
    retrieved from each project (`num_oracles`) and the dimension of the project (3 possible values admitted: `Small`,
    `Medium`, `Large`. Returns a corresponding dictionary containing the information read, in the following format:
        {
            project_id: {
                'num_oracles': int,
                'size_bin': 'Small'|'Medium'|'Large'
            }
        }
    """
    out = {}
    with open(path, "r", encoding="utf-8") as f:
        rdr = csv.DictReader(f)
        for r in rdr:
            pid = r["project_id"]
            out[pid] = {
                "num_oracles": int(r["num_oracles"]),
                "size_bin": r["size_bin"],
            }
    return out


def find_project_files_by_ext(
        data_dir: str,
        pids: List[str],
        ext: str = "jsonl"
) -> Dict[str, List[str]]:
    """
    Searches the files containing the datapoints corresponding to the given project ids (`pids`), and collect them.
    """
    # Collect the list of files representing the dataset, from the `data_dir`
    files = glob.glob(os.path.join(data_dir, f"*.{ext}"))
    # Initialize the dictionary of projects files links
    by = {pid: [] for pid in pids}
    # Iterate over the list of `.jsonl` files within `data_dir`
    for f in files:
        # Get the name of the current file
        name = os.path.basename(f)
        # Extract the project identifier from the name of the current file
        pid = name.replace("oracles-datapoints-", "").split("-")[0]
        # If the project id is in the list of target `pids` append the file to the list of the corresponding `pid`
        # in the dictionary
        if pid in by:
            by[pid].append(f)
    for k in by:
        by[k] = sorted(by[k])
    return by


def split_by_bins(
        meta: Dict[str, Dict]
) -> Dict[str, List[str]]:
    """
    Split the projects processed from `.csv` in bins, representing their dimension.
    """
    bins = {"Small": [], "Medium": [], "Large": []}
    for pid, m in meta.items():
        b = m["size_bin"]
        # If the size label is not recognized, the dimension is set to `Medium`
        if b not in bins: b = "Medium"
        bins[b].append(pid)
    for k in bins: bins[k].sort()
    return bins


def allocate_bin_budgets(
        meta: Dict[str, Dict],
        total_token_budget: int,
        mode: str = "sqrt"
) -> Dict[str, int]:
    """
    Starting from a `total_token_budget` number of tokens, allocate a token budget to each bin in the dataset,
    according to the given `mode`.
    `mode` ∈ {'proportional','equal','sqrt'}
        - proportional: by total oracles per bin
        - equal: equal tokens per bin
        - sqrt: by sqrt(total oracles per bin)
    Returns a dictionary where the key is the dimension of the bin (`Small`, `Medium`, `Large`) and the value
    is the token budget assigned to the bin, according to the given `mode`.
    """
    # Divide the projects, collecting them into `Small`, `Medium`, and `Large` bins
    bins = split_by_bins(meta)
    # Initialize a dictionary containing the total number of oracles within each bin collection
    sizes = {}
    # Iterate over each bin collection (b is the dimension, pids is the list of projects of that dimension)
    for b, pids in bins.items():
        # Compute the total of oracles within the collection, summing the number of oracles within each project
        sizes[b] = sum(meta[pid]["num_oracles"] for pid in pids)
    # If `mode` is `equal`, each bin contribute to the dataset with the same weight
    if mode == "equal":
        w = {b: 1.0 for b in bins}
    # If `mode` is `proportional`, each bin contribute to the dataset with a weight corresponding
    # to the total number of oracles within it
    elif mode == "proportional":
        w = {b: float(sizes[b]) for b in bins}
    # If `mode` is `sqrt`, each bin contribute to the dataset with a weight corresponding
    # to the square root of the total number of oracles within it. In this way, each bin contribute
    # proportionally to their dimension, but without "submerging" the smaller bins
    else:
        w = {b: math.sqrt(max(1, sizes[b])) for b in bins}
    sw = sum(w.values()) or 1.0
    # Assign the total token budget to each bin, according to the weight previously computed
    return {b: int(total_token_budget * (w[b]/sw)) for b in bins}


def allocate_project_budgets(
        meta: Dict[str, Dict],
        bin_tokens: Dict[str, int],
        alpha: float = 0.5,
        min_tokens_per_project: int = 8192
) -> Dict[str, int]:
    """
    Inside each bin, distribute its budget across projects using weight = (num_oracles)^alpha.
    alpha:
        - 0   -> uniform per project
        - 1.0 -> proportional to num_oracles
        - 0.5 -> √-scaling (robust default)
    Return the dictionary of token budget for each project in the bin
    """
    # Divide the projects, collecting them into `Small`, `Medium`, and `Large` bins
    bins = split_by_bins(meta)
    # Initialize a dictionary containing the budget of tokens assigned to each project within the bin,
    # according to their contribution to the total number of oracles within the bin.
    budgets = {}
    # Iterate over each bin collection (b is the dimension, pids is the list of projects of that dimension)
    for b, pids in bins.items():
        # If the bin does not contain projects skip the bin
        if not pids: continue
        # Assign the bin according to the alpha value
        weights = [(pid, max(1, meta[pid]["num_oracles"]) ** alpha) for pid in pids]
        sw = sum(w for _, w in weights) or 1.0
        # Iterate over each tuple of project id (`pid`) and corresponding weight (`w`)
        for pid, w in weights:
            # Compute the number of tokens to assign to the project, given the total budget assigned
            # to the bin and the weights computed for the given project
            share = int(bin_tokens[b] * (w / sw))
            budgets[pid] = max(share, min_tokens_per_project)
    # Return the dictionary of token budget for each project in the bin
    return budgets


def iter_project_samples(
        file_paths: List[str],
        encoding_type: str = "jsonl",
        wrap: bool = False
) -> Iterator[Sample]:
    """
    encoding_type: 'jsonl' --> Iterates over a list of .jsonl file paths, reading each file line by line. Each line is expected to contain a
    JSON object representing a conversation between a user and an assistant.
    encoding_type: 'csv' --> Iterates over a list of .csv file paths, reading the rows of each file. Each row is expected to contain a
    sample object representing an input to the model (src) and the corresponding target answer (tgt).
    The function yields the messages
    from these conversations as lists of dictionaries, allowing for efficient processing of potentially large datasets.
    """
    # Iterate over the `.jsonl` file paths
    for p in file_paths:
        # Read the current file path (the content of the file is a list of json objects corresponding to
        # conversations between the `user` and the `assistant`
        with io.open(p, "r", encoding="utf-8") as f:
            if encoding_type == "jsonl":
                # Iterate over each line in the file path (it corresponds to a single conversation)
                for line in f:
                    try:
                        # Load the current conversation as a json object
                        obj = orjson.loads(line)
                    except Exception:
                        # Skip the conversation sample if not able to process it as a json object
                        continue
                    # Collect the messages between the user and the assistant within the conversation
                    msgs = obj["messages"] if "messages" in obj else obj
                    if isinstance(msgs, list) and msgs:
                        yield msgs
            elif encoding_type == "csv":
                # Read CSV with pandas - handles large fields automatically
                df = pd.read_csv(p, encoding="utf-8", engine="python", on_bad_lines="skip")
                # Normalize column names to lowercase
                df.columns = df.columns.str.lower()

                # Check for required columns
                if "src" not in df.columns or "tgt" not in df.columns:
                    raise ValueError(f"CSV {p} must contain 'src' and 'tgt' columns (case-insensitive).")

                # Iterate over rows
                for _, row in df.iterrows():
                    src = row.get("src", "")
                    tgt = row.get("tgt", "")
                    # Handle NaN values
                    if pd.isna(src):
                        src = ""
                    if pd.isna(tgt):
                        tgt = ""
                    # Convert to string
                    src = str(src) if src else ""
                    tgt = str(tgt) if tgt else ""
                    # Skip empty pairs
                    if (src.strip() == "") and (tgt.strip() == ""):
                        continue
                    if wrap:
                        tgt = "<|type_mask_token|>" + tgt.split(":")[0] + "<|type_mask_token|>" + "<|value_mask_token|>" + tgt.split(":")[1].strip() + "<|value_mask_token|>"
                    src = src.replace("<mask_id>", "<|fill_mask_token|>")
                    src = src.replace("// Junit Version\n", "// Junit Version: ")
                    yield (src, tgt)


def bucket_from_len(
        L: int,
        s_cut: int,
        m_cut: int
) -> int:
    """
    Determines the bucket of a sample, given its token length `L` and the thresholds `s_cut` and `m_cut`.
    Returns the bucket of the sample as integer (0=`short`, 1=`medium`, 2`long`).
    """
    # 0=short,1=med,2=long
    if L <= s_cut: return 0
    if L <= m_cut: return 1
    return 2


def sample_project_tokenized(
        files: List[str],
        target_tokens: int,             # token budget for the project
        encoding_type: str,             # 'jsonl' or 'csv'
        encoder: EncoderInterface,      # encodes one conversation -> (ids, labels)
        rng: random.Random,             # token length thresholds for buckets
        short_cut: int,
        med_cut: int,
        target_mix=(0.2,0.4,0.4),       # desired token share per bucket (short/med/long)
        min_len: int = 300,
        per_bucket_buf: float = 0.10,   # allow up to +10% over each bucket cap
        overflow_keep_p: float = 0.25,  # keep some over-cap items as reservoir
        early_stop_buf: float = 0.15,   # stop scanning around 115% of budget (like before)
        wrap: bool = False
) -> Tuple[List[np.ndarray], List[np.ndarray], int]:
    """
    Samples datapoints from a given project, according to a distribution determined by the conversation lengths, up to
    the limit imposed by the `target_tokens` cap assigned to the project.
    """
    # `target_tokens` define the maximum number of tokens assigned to the project
    # The total number of tokens is distributed in 3 buckets, considering samples
    # of `small`, `medium`, and `large` dimension. The `target_mix` determines the
    # distribution of tokens cap assigned to each bucket. The dimension of the sample
    # is determined by the `short_cut` and `med_cut` values that represents the thresholds
    # between `short` and `medium` samples, and `medium` and `large` samples
    cap = [int(target_tokens*p) for p in target_mix]
    # Soft cap for additional samples in the bucket
    cap_plus = [int(c * (1 + per_bucket_buf)) for c in cap]
    # Tokens counter per bucket
    tok_sum = [0,0,0]
    # Soft total tokens counter (it initially accept a value > `target_tokens`, considering the soft cap)
    soft_total = 0
    # Buckets of samples (it initially accept values <= the soft cap, per bucket)
    soft_buckets = [[],[],[]]
    # Buckets of overflow samples (over-cap samples)
    overflow = [[], [], []]

    # Iterate over the samples of the project
    for sample in iter_project_samples(files, encoding_type, wrap):
        # Encode the messages in the conversation and retrieve the encoded `input_ids` and `label_ids` to
        # fine-tune the model
        ids, lbl = encoder.encode(sample)
        # Discard empty samples
        if ids is None:
            continue
        # Count the tokens within the input
        L = int(len(ids))
        # Filter ultra-short samples (often noise)
        if L < min_len:
            continue
        # Determine the bucket of the sample
        b = bucket_from_len(L, short_cut, med_cut)
        # Prefer filling deficit buckets, but keep additional samples until soft cap
        if tok_sum[b] <= cap_plus[b]:
            # Append the candidate to the corresponding soft bucket (save both the encoded `input_ids`,
            # `label_ids` and tokens length `L` of the `input_ids`
            soft_buckets[b].append((ids,lbl,L))
            # Update the total counter of tokens of the bucket to which the sample belongs
            tok_sum[b] += L
            # Update the soft total
            soft_total += L
        else:
            # Keep some overflow as backfill reservoir (helps skewed corpora)
            if rng.random() < overflow_keep_p:
                overflow[b].append((ids, lbl, L))
        # Early stop: once pools roughly cover the whole project budget, stop scanning
        if soft_total >= int(target_tokens * (1 + early_stop_buf)):
            break
    # Iterate over the three buckets and the overflows backfill reservoir and shuffle them for variety
    for k in range(len(soft_buckets)):
        rng.shuffle(soft_buckets[k])
        rng.shuffle(overflow[k])
    # Define final lists of selected samples (input_ids, labels).
    # Written counts the total number of tokens selected, overall, and per bucket (`written_by_bucket`)
    sel_ids, sel_lbls, written = [], [], 0
    written_by_bucket = [0, 0, 0]
    # Keep track of unused samples
    leftovers = []
    # Iterate over the buckets to select the samples
    for k in range(len(soft_buckets)):
        # Iterate over the samples in the current bucket
        for ids,lbl,L in soft_buckets[k]:
            # If the limit of tokens assigned to the project has been overcome, exit the loop
            if written >= target_tokens:
                break
            # If the soft limit of token for the current bucket has not been overcome, add the sample
            # And update the relative and absolute counters
            if written_by_bucket[k] <= cap_plus[k]:
                sel_ids.append(ids)
                sel_lbls.append(lbl)
                written += L
                written_by_bucket[k] += L
            else:
                leftovers.append((ids,lbl,L))
        # If the limit of tokens assigned to the project has been overcome, exit the loop even if one or more
        # buckets have not been processed yet
        if written >= target_tokens:
            break
    # If after the selection the limit of tokens assigned to the project has not been reached yet, adds additional
    # samples from the backup buckets, if any
    if written < target_tokens:
        # Plus overflow reservoirs
        for k in range(len(soft_buckets)):
            leftovers.extend(overflow[k])
        # Shuffle and greedily fill to target (allow tiny overshoot for small samples)
        rng.shuffle(leftovers)
        # Iterate over the leftover samples and add them up to the limit of tokens assigned to project, if any
        for ids, lbl, L in leftovers:
            if written < target_tokens:
                sel_ids.append(ids)
                sel_lbls.append(lbl)
                written += L
            if written >= target_tokens:
                break
    # Returns the selected samples
    return sel_ids, sel_lbls, written


def build_split(
        split_name: str,
        encoding_type: str,
        pids_path: str,
        data_dir: str,
        out_dir: str,
        model_name_or_path: str,
        model_revision: str,
        max_length: int,
        special_tokens: List[str],
        total_token_budget: int,
        bin_allocation: str,
        alpha: float,
        drop_long: bool,
        tokens_per_shard: int,
        target_mix: Tuple[float,float,float],
        short_cut_frac: float,
        med_cut_frac: float,
        seed: int,
        wrap: bool = False
):
    # Initalize pseudo-random with seed for reproducibility
    rng = random.Random(seed)
    # Get the tokenizer
    tok, tok_vocab_len_before, tok_vocab_len_after = build_tokenizer(
        model_name_or_path,
        max_length,
        model_revision,
        special_tokens,
    )
    # Initialize encoder
    if encoding_type == "jsonl":
        enc = ChatEncoder(
            tok,
            max_len=max_length,
            only_last_turn_loss=True,
            drop_long=drop_long
        )
    else:
        enc = PairEncoder(
            tok,
            max_len=max_length,
            drop_long=drop_long,
            append_eos=True
        )
    # Read .csv with the identifiers of projects to process, and the relative metadata
    meta = read_split_csv(pids_path)
    # Get the list of project identifiers
    pids = sorted(meta.keys())
    # Get the list of files per project, in the dataset
    files_by_pid = find_project_files_by_ext(data_dir, pids, encoding_type)
    # Allocate tokens budget for each bin (`Small`, `Medium`, `Large`, determined by the total number
    # of oracles in each project and the policy applied - `mode`)
    bin_tokens = allocate_bin_budgets(meta, total_token_budget, mode=bin_allocation)
    # Allocate the total number of tokens assigned to each project
    proj_budget = allocate_project_budgets(meta, bin_tokens, alpha=alpha, min_tokens_per_project=max(max_length, 4096))
    # Define thresholds to consider dimension of conversations within project (`small`, `medium`, `large` conversations)
    short_cut = int(max_length * short_cut_frac)   # e.g., 0.33 * 4096
    med_cut   = int(max_length * med_cut_frac)     # e.g., 0.66 * 4096
    # Collect meta data
    meta_out = {
        "split": split_name,
        "model_name_or_path": model_name_or_path,
        "model_revision": model_revision,
        "max_length": max_length,
        "bin_allocation": bin_allocation,
        "alpha": alpha,
        "token_budget": total_token_budget,
        "target_mix": target_mix,
        "length_cuts": {"short": short_cut, "medium": med_cut},
        "pad_token_id": tok.pad_token_id,
        "eos_token_id": tok.eos_token_id,
        "special_tokens_map": tok.special_tokens_map,
        "vocab_size_before": tok_vocab_len_before,
        "vocab_size": tok_vocab_len_after,
        "special_token_ids": { t: tok(t).input_ids[0] for t in special_tokens },
        "wrap": wrap,
        "drop_long": drop_long,
        "num_projects": len(pids),
        "projects": pids
    }
    # Define path where to save tokenizer
    tok_dir = os.path.join(out_dir, "tokenizer")
    # Create tokenizer and output path dirs, if they do not exist
    os.makedirs(out_dir, exist_ok=True)
    os.makedirs(tok_dir, exist_ok=True)
    # Save tokenizer in `tok_dir`
    tok.save_pretrained(tok_dir)
    # Save metadata to output dir
    with open(os.path.join(out_dir, f"{split_name}.meta.json"), "w") as f:
        json.dump(meta_out, f, indent=2)
    with open(os.path.join(out_dir, "base_model.json"), "w") as bf:
        json.dump({"base_model_name_or_path": model_name_or_path,
                   "base_model_revision": model_revision or "main"}, bf, indent=2)
    # Initialize shard writer and auxiliary variables to take into account how many tokens have been written
    # in total and per project
    writer = ShardWriter(out_dir, split_name, tokens_per_shard=tokens_per_shard)
    total_written = 0
    per_project = {}
    # Iterate over each project and process each of them
    for pid in pids:
        # Get the budget cap of tokens assigned to the project
        tokens_cap = proj_budget.get(pid, 0)
        # Get the dataset files related to the project, containing the samples of the project
        files = files_by_pid.get(pid, [])
        # Skip project if no budget is assigned or the projects does not have samples
        if tokens_cap <= 0 or not files:
            continue
        # Collect samples from the project
        ids_list, lbl_list, got = sample_project_tokenized(
            files,
            tokens_cap,
            encoding_type,
            enc,
            rng,
            short_cut,
            med_cut,
            target_mix=target_mix,
            wrap=wrap,
        )
        # Write samples in the current shard
        for ids, lbl in zip(ids_list, lbl_list):
            writer.write(ids, lbl)
        # Update written tokens per project and in total
        total_written += got
        per_project[pid] = got
        # Log stats
        print(f"[{split_name}] {pid} ({meta[pid]['size_bin']}) -> {got:,} tokens; total={total_written:,}")
    # Close shard writer at the end of the process
    writer.close()
    # Save stats related to the sampling and encoding
    with open(os.path.join(out_dir, f"{split_name}.per_project_tokens.json"), "w") as f:
        json.dump(per_project, f, indent=2)
    print(f"[{split_name}] DONE. total_tokens={total_written:,}")


def parse_args():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model_name_or_path", required=True, help="Path to pretrained model or model identifier from huggingface.co")
    ap.add_argument("--model_revision", type=str, default=None, help="HF revision (commit hash or tag) to pin the base tokenizer.")
    ap.add_argument("--encoding_type", choices=["jsonl","csv"], default="jsonl", help="Type of files to process (jsonl with chat messages or csv with src/tgt pairs).")
    ap.add_argument("--data_dir", required=True, help="Path to data directory where JSON files are stored.")
    ap.add_argument("--train_csv", required=True, help="Path to training CSV (the csv contains the list of project identifers to use for training).")
    ap.add_argument("--val_csv", required=True, help="Path to validation CSV (the csv contains the list of project identifers to use for validation).")
    ap.add_argument("--out_dir", required=True, help="Path to output directory.")
    ap.add_argument("--max_length", type=int, default=8192, help="Maximum length of tokens.")
    ap.add_argument("--start_id", type=str, default="<|im_start|>", help="Opening token for a message block (fallback formatter).")
    ap.add_argument("--end_id", type=str, default="<|im_end|>", help="Closing token for a message block (fallback formatter).")
    ap.add_argument("--train_token_budget", type=int, default=2_000_000_000, help="Token budget for training.")
    ap.add_argument("--val_token_budget",   type=int, default=40_000_000, help="Token budget for validation.")
    ap.add_argument("--bin_allocation", choices=["proportional","equal","sqrt"], default="sqrt", help="How to allocate tokens across Small/Medium/Large bins.")
    ap.add_argument("--alpha", type=float, default=0.5, help="Within-bin per-project weighting exponent on num_oracles.")
    ap.add_argument("--target_mix", type=str, default="0.4,0.4,0.2", help="Short,Medium,Long token share per project (comma).")
    ap.add_argument("--short_cut_frac", type=float, default=0.33, help="Short ≤ frac*max_length.")
    ap.add_argument("--med_cut_frac",   type=float, default=0.66, help="Medium ≤ frac*max_length.")
    ap.add_argument("--tokens_per_shard", type=int, default=50_000_000, help="Number of tokens per shard.")
    ap.add_argument("--seed", type=int, default=42, help="Random seed for reproducibility.")
    ap.add_argument("--train_drop_long", action="store_true", help="Whether to drop long tokens in training bins.")
    ap.add_argument("--val_drop_long", action="store_true", help="Whether to drop long tokens in validation bins.")
    ap.add_argument("--wrap", action="store_true", help="Whether to wrap each message in [token][/token] format.")
    return ap.parse_args()

def resolve_snapshot(model_name_or_path: str, model_revision: Optional[str]):
    # Resolve snapshot (commit SHA) for reproducibility
    # Check if the `model_name_or_path` links to a local directory
    if os.path.isdir(model_name_or_path):
        # Set the `resolved_path` to the local directory and the `used_revision` to `local`
        resolved_path = os.path.abspath(model_name_or_path)
        used_revision = "local"
    else:
        # Set the `resolved_path` to the value returned by the api
        resolved_path = snapshot_download(repo_id=model_name_or_path, revision=model_revision)
        # Get the commit SHA of the snapshot
        used_revision = Path(resolved_path).name
    return resolved_path, used_revision

def main():
    # Parse arguments passed to the script
    args = parse_args()
    # Process distribution of bins (`Small`, `Medium`, `Large`)
    mix = tuple(float(x) for x in args.target_mix.split(","))
    assert len(mix) == 3 and abs(sum(mix) - 1.0) < 1e-6, "--target_mix must have 3 values summing to 1.0"
    assert 0.0 < args.short_cut_frac < args.med_cut_frac < 1.0, "Expected 0 < --short_cut_frac < --med_cut_frac < 1"
    # Resolve snapshot once (commit SHA) for reproducibility
    resolved_path, used_revision = resolve_snapshot(args.model_name_or_path, args.model_revision)
    # Process projects for training and encode selected samples. Save them in shards
    build_split(
        "train",
        args.encoding_type,
        args.train_csv,
        args.data_dir,
        args.out_dir,
        resolved_path,
        used_revision,
        args.max_length,
        [ args.start_id, args.end_id, "<|fill_mask_token|>", "<|type_mask_token|>", "<|value_mask_token|>" ] if args.encoding_type == "jsonl" else [ "<|fill_mask_token|>", "<|type_mask_token|>", "<|value_mask_token|>" ],
        args.train_token_budget,
        args.bin_allocation,
        args.alpha,
        args.train_drop_long,
        args.tokens_per_shard,
        mix,
        args.short_cut_frac,
        args.med_cut_frac,
        args.seed,
        args.wrap
    )
    # Process projects for validation and encode selected samples. Save them in shards
    build_split(
        "val",
        args.encoding_type,
        args.val_csv,
        args.data_dir,
        args.out_dir,
        resolved_path,
        used_revision,
        args.max_length,
        [ args.start_id, args.end_id, "<|fill_mask_token|>", "<|type_mask_token|>", "<|value_mask_token|>" ] if args.encoding_type == "jsonl" else [ "<|fill_mask_token|>", "<|type_mask_token|>", "<|value_mask_token|>" ],
        args.val_token_budget,
        args.bin_allocation,
        args.alpha,
        args.val_drop_long,
        max(10_000_000, args.tokens_per_shard // 5),
        mix,
        args.short_cut_frac,
        args.med_cut_frac,
        args.seed + 1,
        args.wrap
    )

if __name__ == "__main__":
    main()
