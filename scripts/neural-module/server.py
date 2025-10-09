#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import argparse
import json
import os
import threading
from queue import Queue

import torch
from flask import Flask, make_response, request
from huggingface_hub import snapshot_download
from peft import PeftModel
from transformers import AutoModelForCausalLM, AutoTokenizer

# Optional: safetensors for quick shape probing
try:
    from safetensors.torch import load_file as safetensors_load_file
except Exception:
    safetensors_load_file = None

# Your project modules
import predictor
from predictor import TokenNotFoundException


# -------------------------
# HTTP server wrapper
# -------------------------

class Server:
    def __init__(self, device, model, tokenizer, max_src_length: int, max_tgt_length: int):
        self._app = Flask(__name__)
        self._device = device
        self._model = model
        self._tokenizer = tokenizer
        self._max_src_length = int(max_src_length)
        self._max_tgt_length = int(max_tgt_length)
        self._request_queue = Queue()
        self._safe_dir = os.environ.get("SAFE_JSON_DIR", None)
        self.setup_routes()

    def next_token(self):
        filename = request.args.get('filename')
        if not filename:
            return 'Missing filename parameter', 400

        # Optional: restrict to SAFE_JSON_DIR if provided
        if self._safe_dir:
            try:
                real = os.path.realpath(filename)
                root = os.path.realpath(self._safe_dir)
                if not real.startswith(root + os.sep):
                    return 'Forbidden filename path', 403
            except Exception:
                return 'Invalid filename', 400

        # Enqueue and wait
        task_event = threading.Event()
        self._request_queue.put({'filename': filename, 'event': task_event})
        task_event.wait()
        task_result = getattr(task_event, "result", None) or {}

        if 'error' in task_result:
            response = make_response(task_result['error'], 404)
            response.headers['Content-Type'] = 'text/plain'
            return response
        token = task_result.get('token', '')
        response = make_response(token)
        response.headers['Content-Type'] = 'text/plain'
        return response

    def process_request(self):
        while True:
            task = self._request_queue.get()
            filename = task['filename']
            try:
                token = predictor.predict_next_token(
                    self._device, filename, self._model, self._tokenizer,
                    self._max_src_length, self._max_tgt_length
                )
                task['token'] = token
                print("Processed request for:", filename)
            except TokenNotFoundException as e:
                print("Error processing request for:", filename, "-", str(e))
                task['error'] = str(e.predicted_token)
            except Exception as e:
                print("Unhandled error processing request for:", filename, "-", repr(e))
                task['error'] = f"Unhandled error: {e}"

            task['event'].result = task
            task['event'].set()
            self._request_queue.task_done()

    def setup_routes(self):
        self._app.add_url_rule('/api/next_token', 'next_token', self.next_token, methods=['GET'])

    def run(self, port):
        worker_thread = threading.Thread(target=self.process_request, daemon=True)
        worker_thread.start()
        self._app.run(port=port)


# -------------------------
# Model setup helpers
# -------------------------

def _is_adapter_dir(path: str) -> bool:
    return (
        os.path.isfile(os.path.join(path, "adapter_config.json")) and
        (os.path.isfile(os.path.join(path, "adapter_model.safetensors")) or
         os.path.isfile(os.path.join(path, "adapter_model.bin")))
    )

def _read_adapter_config(adapter_dir: str) -> dict:
    try:
        with open(os.path.join(adapter_dir, "adapter_config.json"), "r") as f:
            return json.load(f)
    except Exception:
        return {}

def _resolve_snapshot(repo_or_path: str, revision: str | None) -> str:
    if os.path.isdir(repo_or_path):
        return os.path.abspath(repo_or_path)
    return snapshot_download(repo_id=repo_or_path, revision=revision)

def _pick_attn_impl() -> str:
    # CPU box -> SDPA by default
    return "sdpa"

def _pad_up(n: int, multiple: int) -> int:
    if multiple <= 1:
        return n
    return ((n + multiple - 1) // multiple) * multiple

def _expected_rows_from_adapter(adapter_dir: str) -> int | None:
    """
    Try to infer the expected embedding/head row count from the adapter weights.
    Prefers safetensors; falls back to .bin if needed.
    """
    st_path = os.path.join(adapter_dir, "adapter_model.safetensors")
    if safetensors_load_file and os.path.exists(st_path):
        try:
            sd = safetensors_load_file(st_path, device="cpu")
            for k in (
                "base_model.model.model.embed_tokens.weight",
                "model.model.embed_tokens.weight",
                "embed_tokens.weight",
                "base_model.model.lm_head.weight",
                "lm_head.weight",
            ):
                if k in sd:
                    return int(sd[k].shape[0])
        except Exception:
            pass

    # Lightweight fallback: try to load a torch .bin (may be slower/more memory)
    bin_path = os.path.join(adapter_dir, "adapter_model.bin")
    if os.path.exists(bin_path):
        try:
            # torch>=2.1 supports weights_only=True; harmless if ignored by older torch
            sd = torch.load(bin_path, map_location="cpu", weights_only=True)
            for k in (
                "base_model.model.model.embed_tokens.weight",
                "model.model.embed_tokens.weight",
                "embed_tokens.weight",
                "base_model.model.lm_head.weight",
                "lm_head.weight",
            ):
                if k in sd:
                    return int(sd[k].shape[0])
        except Exception:
            pass
    return None

def _pick_tokenizer_src(tokenizer_arg: str | None, adapter_dir: str | None, base_snap: str) -> str:
    """
    Priority:
      1) Explicit --tokenizer_name_or_path if it points to a dir with tokenizer files
      2) adapter_dir/tokenizer if present
      3) base snapshot
    """
    cand = None
    if tokenizer_arg and os.path.isdir(tokenizer_arg):
        cand = tokenizer_arg
    elif adapter_dir and os.path.isdir(os.path.join(adapter_dir, "tokenizer")):
        cand = os.path.join(adapter_dir, "tokenizer")
    else:
        cand = base_snap

    # sanity: check tokenizer.json or vocab files exist; else fall back to base_snap
    has_tok = any(os.path.exists(os.path.join(cand, f)) for f in (
        "tokenizer.json", "tokenizer_config.json", "vocab.json", "spiece.model"
    ))
    return cand if has_tok else base_snap


# -------------------------
# Model/Tokenizer bootstrap
# -------------------------

def setup_model(tokenizer_name_or_path,
                model_name_or_path,
                model_revision,
                prefer_4bit=True,
                try_merge_lora=False):
    """
    - If model_name_or_path is a LoRA adapter directory, load the base snapshot (prefer adapter's revision),
      resize embeddings to match adapter (or len(tokenizer) padded to 8), then attach LoRA.
    - Otherwise, load a merged base model as-is.
    """
    attn_impl = _pick_attn_impl()

    if _is_adapter_dir(model_name_or_path):
        # ----- LoRA path -----
        acfg = _read_adapter_config(model_name_or_path)
        base_repo = acfg.get("base_model_name_or_path")
        # Prefer the revision baked into the adapter; fall back to CLI/model_revision
        base_rev = acfg.get("base_model_revision") or model_revision
        if not base_repo:
            raise RuntimeError("adapter_config.json missing 'base_model_name_or_path'.")

        base_snap = _resolve_snapshot(base_repo, base_rev)

        # Tokenizer: prefer explicit arg, then adapter/tokenizer, else base
        tok_src = _pick_tokenizer_src(tokenizer_name_or_path, model_name_or_path, base_snap)
        tok = AutoTokenizer.from_pretrained(tok_src, use_fast=True, trust_remote_code=True)
        if tok.pad_token_id is None:
            tok.pad_token = tok.eos_token
        # Do NOT forcibly change padding_side; keep whatever the saved tokenizer has

        use_cuda = torch.cuda.is_available()

        # IMPORTANT: when CPU-only, DO NOT use device_map="auto" (causes offload+meta).
        # Materialize everything on CPU so PEFT can attach LoRA weights reliably.
        base = AutoModelForCausalLM.from_pretrained(
            base_snap,
            attn_implementation=attn_impl,
            torch_dtype=("auto" if use_cuda else torch.float32),
            device_map=("auto" if use_cuda else {"": "cpu"}),  # <-- force CPU, no offload, when no CUDA
            low_cpu_mem_usage=False,  # <-- ensure real tensors, not meta
            trust_remote_code=True,
        )

        # Safety check: bail early if anything somehow remained meta (shouldn’t happen with the mapping above)
        if any(getattr(p, "is_meta", False) for _, p in base.named_parameters()):
            raise RuntimeError(
                "Base model still has meta parameters after load. "
                "On CPU, ensure device_map={'': 'cpu'} and low_cpu_mem_usage=False."
            )

        # Debug print: base vocab vs tokenizer
        try:
            cfg = json.load(open(os.path.join(base_snap, "config.json"), "r"))
            embed_rows = base.get_input_embeddings().num_embeddings
            print(f"[serve] base_repo={base_repo} rev={base_rev} | config.vocab_size={cfg.get('vocab_size')} "
                  f"| embed_rows={embed_rows} | tok_len={len(tok)}")
        except Exception:
            pass

        # Compute expected rows:
        # 1) If adapter stores embed/head tensors, use their first dim.
        # 2) Else mimic training: len(tokenizer) padded to multiple of 8.
        want_rows = _expected_rows_from_adapter(model_name_or_path)
        if want_rows is None:
            want_rows = _pad_up(len(tok), 8)

        base_rows = base.get_input_embeddings().num_embeddings
        if want_rows != base_rows:
            print(f"[serve] resizing embeddings {base_rows} -> {want_rows} to match adapter/tokenizer")
            base.resize_token_embeddings(want_rows)
            base.config.vocab_size = want_rows

        # Attach LoRA adapter
        peft_kwargs = {}
        if not torch.cuda.is_available():
            # Prevent PEFT/Accelerate from creating an offload device_map that needs an offload_dir
            peft_kwargs["device_map"] = {"": "cpu"}
            peft_kwargs["offload_folder"] = None  # be explicit

        model = PeftModel.from_pretrained(base, model_name_or_path, **peft_kwargs)

        if any(getattr(p, "is_meta", False) for _, p in base.named_parameters()):
            raise RuntimeError("Base model still has meta parameters; ensure device_map={'': 'cpu'} on CPU.")

        if any(getattr(p, "is_meta", False) for _, p in model.named_parameters()):
            raise RuntimeError("Final model still has meta parameters; PEFT shouldn’t offload on CPU.")

    else:
        # ----- Merged model / plain base path -----
        m_snap = _resolve_snapshot(model_name_or_path, model_revision)
        tok_src = _pick_tokenizer_src(tokenizer_name_or_path, None, m_snap)
        tok = AutoTokenizer.from_pretrained(tok_src, use_fast=True, trust_remote_code=True)
        if tok.pad_token_id is None:
            tok.pad_token = tok.eos_token

        model = AutoModelForCausalLM.from_pretrained(
            m_snap,
            attn_implementation=_pick_attn_impl(),
            torch_dtype="auto",
            device_map="auto",
            low_cpu_mem_usage=False,
            trust_remote_code=True,
        )

    # Finalize generation config
    if hasattr(model, "generation_config"):
        model.generation_config.pad_token_id = tok.pad_token_id
        model.generation_config.eos_token_id = tok.eos_token_id
        if getattr(tok, "bos_token_id", None) is not None:
            model.generation_config.bos_token_id = tok.bos_token_id

    return model, tok


# -------------------------
# Entrypoint
# -------------------------

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", default=5000, type=str, required=True, help="The server port.")
    parser.add_argument("--tokenizer_name_or_path", default=None, type=str,
                        help="Path to the tokenizer to use (prefer the one saved during data build or training).")
    parser.add_argument("--model_name_or_path", default=None, type=str,
                        help="Path to a merged model or a LoRA adapter directory.")
    parser.add_argument("--model_revision", default=None, type=str,
                        help="HF revision (commit hash or tag) of the base model to pin.")
    parser.add_argument("--max_src_length", default=8192, type=int,
                        help="Maximum total input length after tokenization.")
    parser.add_argument("--max_tgt_length", default=2048, type=int,
                        help="Maximum generated length.")
    args = parser.parse_args()

    device = torch.device("cuda:0" if torch.cuda.is_available() else "cpu")
    if torch.cuda.is_available():
        print(f'There are {torch.cuda.device_count()} GPU(s) available.')
        print(f'Selected GPU: {torch.cuda.get_device_name(0)}')
    else:
        print('No GPU available, using the CPU instead.')

    # Build model + tokenizer
    model, tokenizer = setup_model(
        args.tokenizer_name_or_path,
        args.model_name_or_path,
        args.model_revision,
        prefer_4bit=True,
        try_merge_lora=True,
    )

    # Spin up the HTTP server
    server = Server(device, model, tokenizer, args.max_src_length, args.max_tgt_length)
    server.run(args.port)
