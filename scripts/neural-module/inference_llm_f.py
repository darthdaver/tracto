#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Simplified oracle inference script.
Reads prompts, generates oracles using a fine-tuned model, and saves results.
"""

import argparse
import json
import os
import sys
import csv
import time
import subprocess

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer
from peft import PeftModel
from huggingface_hub import snapshot_download

# Optional: safetensors for quick shape probing
try:
    from safetensors.torch import load_file as safetensors_load_file
except Exception:
    safetensors_load_file = None

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


@torch.no_grad()
def generate_oracle(
        model,
        tokenizer,
        prompt: str,
        max_new_tokens: int = 512,
        temperature: float = 0.2,
        top_p: float = 0.95,
        top_k: int = 50,
) -> str:
    """
    Generate oracle completion from FIM prompt.

    Args:
        model: Loaded model
        tokenizer: Loaded tokenizer
        prompt: FIM-formatted prompt ending with <|fim_middle|>
        max_new_tokens: Maximum tokens to generate
        temperature: Sampling temperature
        top_p: Nucleus sampling parameter
        top_k: Top-k sampling parameter

    Returns:
        Generated oracle string
    """
    # Encode prompt
    input_ids = tokenizer.encode(prompt, add_special_tokens=False, return_tensors="pt")
    input_ids = input_ids.to(model.device)

    # Generate
    outputs = model.generate(
        input_ids,
        max_new_tokens=max_new_tokens,
        temperature=temperature,
        top_p=top_p,
        top_k=top_k,
        do_sample=True if temperature > 0 else False,
        pad_token_id=tokenizer.pad_token_id,
        eos_token_id=tokenizer.eos_token_id,
    )

    # Decode only the generated part
    generated_ids = outputs[0][input_ids.shape[1]:]
    generated_text = tokenizer.decode(generated_ids, skip_special_tokens=True)

    return generated_text.strip()


def main():
    parser = argparse.ArgumentParser()

    # Model arguments
    parser.add_argument("--tokenizer_name_or_path", default=None, type=str,
                        help="Path to the tokenizer")
    parser.add_argument("--model_name_or_path", default=None, type=str,
                        help="Path to fine-tuned model or PEFT adapter")
    parser.add_argument("--model_revision", default=None, type=str,
                        help="HF revision (commit hash or tag)")

    # Generation arguments
    parser.add_argument("--max_src_length", default=8192, type=int,
                        help="Maximum input length")
    parser.add_argument("--max_tgt_length", default=2048, type=int,
                        help="Maximum generated length")

    # Data arguments
    parser.add_argument("--oracles_dataset_dir_path", default=None, type=str,
                        help="Path to directory containing oracle dataset JSON files")
    parser.add_argument("--output_path", default=None, type=str,
                        help="Path to output directory for generated oracles")
    parser.add_argument("--prompt_config", default=None, type=str,
                        help="Prompt config number/ID")

    args = parser.parse_args()

    # Device setup
    device = torch.device("cuda:0" if torch.cuda.is_available() else "cpu")
    if torch.cuda.is_available():
        print(f'There are {torch.cuda.device_count()} GPU(s) available.')
        print(f'Selected GPU: {torch.cuda.get_device_name(0)}')
    else:
        print('No GPU available, using the CPU instead.')

    # Load model and tokenizer
    print("\n[INFO] Loading model and tokenizer...")
    model, tokenizer = setup_model(
        args.tokenizer_name_or_path,
        args.model_name_or_path,
        args.model_revision,
        prefer_4bit=True,
        try_merge_lora=True,
    )
    # Check LoRA adapter loaded if applicable
    _verify_lora_loaded(model)

    # Construct paths for prompt generation (matching your original structure)
    script_dir = os.path.dirname(os.path.abspath(__file__))
    script_prompt = os.path.join(script_dir, "..", "prompt", "prompt_qwen.py")

    # Collect input files
    if os.path.isdir(args.oracles_dataset_dir_path):
        raw_dataset_files = []
        for root, dirs, files in os.walk(args.oracles_dataset_dir_path):
            for file in files:
                if file.endswith('.json'):
                    raw_dataset_files.append(os.path.join(root, file))
    else:
        raw_dataset_files = [args.oracles_dataset_dir_path]

    print(f"\n[INFO] Found {len(raw_dataset_files)} JSON file(s) to process")

    # Create output directory
    if not os.path.exists(args.output_path):
        os.makedirs(args.output_path)

    # Process each file
    for input_file_path in raw_dataset_files:
        repo_id = os.path.dirname(input_file_path).split('/')[-1]
        output_file_path = os.path.join(args.output_path, f"llm_f-{repo_id}-oracles.json")
        print(f'\n{"=" * 80}')
        print(f'Processing file: {os.path.basename(input_file_path)}')
        print(f'Repository ID: {repo_id}')
        print(f'Output will be saved to: {output_file_path}')
        print(f'{"=" * 80}\n')

        # Construct prompt paths using config ID
        output_prompt_path = os.path.join(
            script_dir, "..", "prompt", "output-llm-f", args.prompt_config, repo_id
        )
        config_path = os.path.join(
            script_dir, "..", "prompt", "resources", "config",
            args.prompt_config, "ewash_config_qwen_oracles_inference.json"
        )

        # Load oracle dataset
        with open(input_file_path, "r", encoding="utf-8") as f:
            oracle_dataset = json.load(f)

        inferred_oracles = []
        total_datapoints = sum(len(o["datapoints"]) for o in oracle_dataset)
        processed = 0

        # Process each oracle datapoint
        for oracle_datapoint in oracle_dataset:
            tc = oracle_datapoint["testClass"]
            for dp in oracle_datapoint["datapoints"]:
                single_datapoint = {
                    "testClass": tc,
                    "focalClass": oracle_datapoint["focalClass"],
                    "datapoints": [dp],
                    "junitVersion": oracle_datapoint["junitVersion"]
                }

                single_datapoint_folder_path = os.path.join(os.path.dirname(args.oracles_dataset_dir_path),"llm-f")

                if not os.path.exists(single_datapoint_folder_path):
                    os.makedirs(single_datapoint_folder_path)

                single_datapoint_file_path = os.path.join(single_datapoint_folder_path, f"{os.path.basename(input_file_path)}")

                with open(single_datapoint_file_path, "w", encoding="utf-8") as json_f:
                    json.dump([single_datapoint], json_f)

                processed += 1
                tp = dp["testPrefix"]
                inference_info = {
                    "test_id": f"{tc['identifier']}-{tp['identifier']}-{processed}",
                    "test_class_path": tc["filePath"],
                    "class_name": tc["identifier"],
                    "test_name": tp["identifier"],
                    "signature": tp["signature"],
                    "tp_body": tp["body"],
                    "tgt": dp.get("target", ""),
                    "generated": "",
                    "status": "",
                    "time": -1,
                    "message": ""
                }

                try:
                    start_time = time.time()

                    print(f"[{processed}/{total_datapoints}] Processing: {tc['identifier']}.{tp['identifier']}")

                    # Generate prompt using external script
                    subprocess.run(
                        [
                            sys.executable,
                            script_prompt,
                            "--input", single_datapoint_file_path,
                            "--output", output_prompt_path,
                            "--config", config_path
                        ],
                        check=True,
                        capture_output=True
                    )

                    # Read generated prompt
                    prompt_file_path = os.path.join(
                        output_prompt_path,
                        "prompt",
                        os.path.basename(input_file_path.replace(".json", ".csv"))
                    )

                    # Read prompt from CSV (no header)
                    with open(prompt_file_path, 'r', encoding='utf-8') as f:
                        csv_reader = csv.reader(f)
                        prompt = None
                        for idx, row in enumerate(csv_reader):
                            if idx == 0:
                                # Skip header
                                continue
                            if len(row) >= 2:
                                prompt = row[1]  # Prompt in second column
                                break

                    if not prompt:
                        raise ValueError("No prompt found in CSV file")

                    # Generate oracle
                    generated_oracle = generate_oracle(
                        model,
                        tokenizer,
                        prompt,
                        max_new_tokens=args.max_tgt_length,
                        temperature=0.2,
                    )

                    elapsed_time = time.time() - start_time

                    inference_info["generated"] = generated_oracle
                    inference_info["status"] = "OK"
                    inference_info["time"] = elapsed_time

                    print(f"  ✓ Generated: {generated_oracle[:80]}...")
                    print(f"  ⏱ Time: {elapsed_time:.2f}s")

                except Exception as e:
                    elapsed_time = time.time() - start_time
                    inference_info["status"] = "ERROR"
                    inference_info["message"] = str(e)
                    inference_info["time"] = elapsed_time
                    print(f"  ✗ Error: {str(e)}")

                inferred_oracles.append(inference_info)

        # Save results
        with open(output_file_path, "w", encoding="utf-8") as f:
            json.dump(inferred_oracles, f, indent=2, ensure_ascii=False)

        # Print summary
        success_count = sum(1 for o in inferred_oracles if o["status"] == "success")
        error_count = sum(1 for o in inferred_oracles if o["status"] == "error")
        total_time = sum(o["time"] for o in inferred_oracles if o["time"] > 0)

        print(f'\n{"=" * 80}')
        print(f"SUMMARY for {repo_id}")
        print(f'{"=" * 80}')
        print(f"Total datapoints: {total_datapoints}")
        print(f"Successful: {success_count}")
        print(f"Errors: {error_count}")
        print(f"Total time: {total_time:.2f}s")
        print(f"Average time: {total_time / total_datapoints:.2f}s per sample")
        print(f"Results saved to: {output_file_path}")
        print(f'{"=" * 80}\n')

    print("\n[INFO] All files processed successfully!")


if __name__ == '__main__':
    main()