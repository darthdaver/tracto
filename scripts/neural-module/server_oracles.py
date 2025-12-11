#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import argparse, json, os, threading, subprocess, sys, csv
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

# Increase CSV field size limit to maximum possible
field_size_limit = sys.maxsize
while True:
    try:
        csv.field_size_limit(field_size_limit)
        break
    except OverflowError:
        field_size_limit = int(field_size_limit / 10)


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


def _resolve_snapshot(repo_or_path: str, revision: str = None) -> str:
    """
    Resolve a HuggingFace repo ID or local path to an actual snapshot directory.
    Args:
        repo_or_path: Either an HF repo ID (e.g. "Qwen/Qwen2.5-Coder-1.5B")
                      or a local filesystem path
        revision: Optional revision (commit hash/tag) for HF repos
    Returns:
        Absolute path to the model directory
    """
    # Clean up spurious quotes, if any
    repo_or_path = repo_or_path.strip("'\"")

    # If the path exists locally, use it directly
    if os.path.isdir(repo_or_path):
        resolved = os.path.abspath(repo_or_path)
        print(f"[serve] Using local model path: {resolved}")
        return resolved
    # Otherwise, download from HF Hub
    print(f"[serve] Downloading/verifying HF repo: {repo_or_path} (revision: {revision})")
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


def _verify_lora_loaded(model):
    """
    Verify that a LoRA adapter is properly loaded into the model.
    """
    print("\n[DEBUG] LoRA Adapter Verification:")

    # 1. Verify if model is a PeftModel
    from peft import PeftModel
    is_peft = isinstance(model, PeftModel)
    print(f"  - Is PeftModel: {is_peft}")

    if not is_peft:
        print("  ❌ WARNING: Model is NOT a PeftModel!")
        return False

    # 2. Check trainable parameters
    trainable_params = 0
    all_params = 0
    for name, param in model.named_parameters():
        all_params += param.numel()
        if param.requires_grad:
            trainable_params += param.numel()
            # Stampa i primi 5 parametri LoRA
            if trainable_params <= 5 * param.numel():
                print(f"  - LoRA param: {name} | shape: {param.shape} | requires_grad: {param.requires_grad}")

    print(f"  - Total params: {all_params:,}")
    print(f"  - Trainable (LoRA) params: {trainable_params:,}")
    print(f"  - Trainable %: {100 * trainable_params / all_params:.4f}%")

    # 3. Check adapter config
    if hasattr(model, 'peft_config'):
        print(f"  - Adapter config: {dict(model.peft_config)}")

    # 4. Check active adapters
    if hasattr(model, 'active_adapters'):
        print(f"  - Active adapters: {model.active_adapters}")

    print("  ✅ LoRA adapter is loaded!\n")
    return trainable_params > 0


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
        model.set_adapter("default")

        try:
            print("[serve][peft] active_adapters:",
                  model.get_active_adapters() if hasattr(model, "get_active_adapters") else None)
            print("[serve][peft] peft_config keys:", list(getattr(model, "peft_config", {}).keys()))
            # A quick param count diff (LoRA adds a small # of trainable params)
            base_params = sum(p.numel() for p in base.parameters())
            all_params = sum(p.numel() for p in model.parameters())
            print(
                f"[serve][peft] base_params={base_params:,} | with_lora={all_params:,} | delta={all_params - base_params:,}")
        except Exception as e:
            print("[serve][peft] debug error:", repr(e))

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
    parser.add_argument("--oracles_dataset_dir_path", default=None, type=str,
                        help="Path to a directory containing oracle datasets.")
    parser.add_argument("--output_path", default=None, type=str,
                        help="Path to a directory containing oracle datasets.")
    parser.add_argument("--prompt_config", default=None, type=str,
                        help="Prompt config number.")
    args = parser.parse_args()

    device = torch.device("cuda:0" if torch.cuda.is_available() else "cpu")
    if torch.cuda.is_available():
        print(f'There are {torch.cuda.device_count()} GPU(s) available.')
        print(f'Selected GPU: {torch.cuda.get_device_name(0)}')
    else:
        print('No GPU available, using the CPU instead.')

    # Build model + tokenizer
    # model, tokenizer = setup_model(
    #     args.tokenizer_name_or_path,
    #     args.model_name_or_path,
    #     args.model_revision,
    #     prefer_4bit=True,
    #     try_merge_lora=True,
    # )
    # # Check LoRA adapter loaded if applicable
    # _verify_lora_loaded(model)

    # Collect files to process
    if os.path.isdir(args.oracles_dataset_dir_path):
        raw_dataset_files = []
        for root, dirs, files in os.walk(args.oracles_dataset_dir_path):
            for file in files:
                if file.endswith('.json'):
                    raw_dataset_files.append(os.path.join(root, file))
    else:
        raw_dataset_files = [args.oracles_dataset_dir_path]

    if not os.path.exists(args.output_path):
        os.makedirs(args.output_path)

    for input_file_path in raw_dataset_files:
        repo_id = os.path.dirname(input_file_path).split('/')[-1]
        output_file_path = os.path.join(args.output_path, "llm_finetuned-" + repo_id + "-oracles.json")
        print(f'Inferring oracles from file {os.path.basename(input_file_path)} from repo id {repo_id} (the final json will be save here: {output_file_path})')
        script_prompt = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "prompt", "prompt_qwen.py")
        output_prompt_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "prompt", "output-llm-f", args.prompt_config, repo_id)
        config_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "prompt", "resources", "config", args.prompt_config, "ewash_config_qwen_oracles_inference.json")
        with open(input_file_path, "r", encoding="utf-8") as f:
            oracle_dataset = json.load(f)

        inferred_oracles = []

        for idx_o, oracle_datapoint in enumerate(oracle_dataset):
            tc = oracle_datapoint["testClass"]
            for idx_dp, dp in enumerate(oracle_datapoint["datapoints"]):
                tp = dp["testPrefix"]
                inference_info = {
                    "test_id": f"{tc["identifier"]}-{dp["identifier"]}-{dp}",
                    "test_class_path": tc["filePath"],
                    "class_name": tc["identifier"],
                    "test_name": tp["identifier"],
                    "signature": tp["signature"],
                    "tp_body": tp["body"],
                    "tgt": dp["target"],
                    "generated": "",
                    "status": "",
                    "time": -1,
                    "message": ""
                }
                subprocess.run(
                    [sys.executable, script_prompt, "--input", input_file_path, "--output", output_prompt_path, "--config",
                     config_path ],
                    check=True  # raise CalledProcessError if the script exits non-zero
                )
                prompt_file_path = os.path.join(output_prompt_path, "prompt", os.path.basename(input_file_path))

                with open(prompt_file_path, 'r', encoding='utf-8') as f:
                    # CSV has no header, read as raw rows
                    csv_reader = csv.reader(f)
                    prompt = ""
                    for row in csv_reader:
                        prompt = row[1]

