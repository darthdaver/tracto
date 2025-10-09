# -*- coding: utf-8 -*-

import json
import re
import torch
from typing import List, Tuple
from transformers import PreTrainedModel, PreTrainedTokenizer

class TokenNotFoundException(Exception):
    def __init__(self, message, predicted_token=None):
        super().__init__(message)
        self.predicted_token = predicted_token

# Oracle evaluator eligible tokens
assert_token = "assertTrue("
no_assertion_token = "// No assertion possible"
oracle_evaluator_eligible_tokens = [assert_token, no_assertion_token]


# ---------------- Chat formatting (Qwen-style) ---------------- #

def _qwen_chat_ids_for_inference(
    tokenizer: PreTrainedTokenizer,
    system_text: str,
    user_text: str,
    assistant_prefix: str
) -> Tuple[torch.Tensor, torch.Tensor, int]:
    """
    Build tokens like your training encoder:
      <|im_start|> system\n SYS_TEXT <|im_end|>\n
      <|im_start|> user\n   USER_TEXT <|im_end|>\n
      <|im_start|> assistant\n ASSISTANT_PREFIX
    (assistant turn is left open: no <|im_end|> so generation continues)
    """
    im_start = tokenizer("<|im_start|>").input_ids[0]
    im_end   = tokenizer("<|im_end|>").input_ids[0]
    nl_ids   = tokenizer("\n", add_special_tokens=False).input_ids[-1:]

    role_system = tokenizer("system", add_special_tokens=False).input_ids + nl_ids
    role_user   = tokenizer("user", add_special_tokens=False).input_ids + nl_ids
    role_asst   = tokenizer("assistant", add_special_tokens=False).input_ids + nl_ids

    ids: List[int] = []

    # system
    sys_ids = [im_start] + role_system \
              + tokenizer(system_text, add_special_tokens=False).input_ids \
              + [im_end] + nl_ids
    ids += sys_ids

    # user
    usr_ids = [im_start] + role_user \
              + tokenizer(user_text, add_special_tokens=False).input_ids \
              + [im_end] + nl_ids
    ids += usr_ids

    # assistant (open)
    asst_open = [im_start] + role_asst \
                + tokenizer(assistant_prefix, add_special_tokens=False).input_ids
    ids += asst_open

    input_ids = torch.tensor([ids], dtype=torch.long)
    attention = torch.ones_like(input_ids)
    return input_ids, attention, im_end


# ---------------- Pre-processing of datapoint ---------------- #

def process_dataset(tokens_datapoint: dict):
    """
    Returns (system_text, user_text, assistant_prefix, eligible_tokens).
    """
    oracle_type_mapping = {
        "EXCEPT_POST": "Exceptional postcondition",
        "NORMAL_POST": "Postcondition",
        "PRE": "Precondition"
    }
    oracle_type = oracle_type_mapping.get(tokens_datapoint.get('oracleType', ''), "")
    javadoc_tag = re.sub(r"\n *", " ", tokens_datapoint.get('javadocTag', ''))
    method_javadoc = tokens_datapoint.get('methodJavadoc', '').strip()
    if method_javadoc != "":
        method_javadoc = "\n" + re.sub(r"\n +", "\n ", method_javadoc)
    method_source_code = tokens_datapoint.get("methodSourceCode", "")
    oracle_so_far = tokens_datapoint.get("oracleSoFar", "")

    additional_info = ""
    elig_tokens_full = tokens_datapoint.get('eligibleTokens', []) or []
    if any(t.get("tokenClass") in ["MethodName", "ClassField"] for t in elig_tokens_full):
        additional_info += "\n\n// Additional context:\n"
        for t in elig_tokens_full:
            if t.get("tokenClass") in ["MethodName", "ClassField"]:
                info = t.get("tokenInfo", ["", "", ""])
                txt = (info[2] if len(info) > 2 else "").strip()
                additional_info += re.sub(r"\n +", "\n ", txt) + "\n"

    # Map eligible tokens to raw tokens (strip any aux info)
    eligible_tokens = [t.get('token', '') for t in elig_tokens_full if 'token' in t]

    # The ';' is not eligible immediately after 'assertTrue('
    if oracle_so_far == assert_token and ";" in eligible_tokens:
        eligible_tokens = [t for t in eligible_tokens if t != ";"]

    # If oracle is empty we are in the evaluator step
    if oracle_so_far == "":
        eligible_tokens = oracle_evaluator_eligible_tokens

    # user prompt mirrors your original string content
    user_text = (
        f'// {oracle_type}: "{javadoc_tag}"\n'
        f'// Next possible tokens: {eligible_tokens}\n'
        f'// Assertion:\n{oracle_so_far}\n\n'
        f'// Method under test:{method_javadoc}\n{method_source_code}{additional_info}'
    )

    system_text = (
        "You are a testing developer assistant. You must help to automatize the process of "
        "generating test oracles, token-by-token."
    )

    # For generation, assistant starts with the oracle_so_far so the model continues it
    assistant_prefix = oracle_so_far

    return system_text, user_text, assistant_prefix, eligible_tokens


# ---------------- Inference ---------------- #

def _prefix_match(generated: str, candidates: List[str]) -> str | None:
    """
    Return the first candidate that is a prefix of generated (after a light left-strip).
    """
    t = generated.lstrip()
    for c in candidates:
        if t.startswith(c):
            return c
    for c in candidates:
        if generated.startswith(c):
            return c
    return None


def infer(
    device,
    model: PreTrainedModel,
    input_ids: torch.Tensor,
    attention_mask: torch.Tensor,
    tokenizer: PreTrainedTokenizer,
    eligible_tokens: List[str],
    max_tgt_length: int,
    extra_eos_id: int | None = None,
) -> str:
    model.eval()
    # stop on EOS or <|im_end|>
    eos_ids = []
    if tokenizer.eos_token_id is not None:
        eos_ids.append(tokenizer.eos_token_id)
    if extra_eos_id is not None:
        eos_ids.append(extra_eos_id)
    eos_ids = list(sorted(set(eos_ids)))

    gen_kwargs = {
        "max_new_tokens": int(max_tgt_length),
        "num_beams": 1,
        "do_sample": False,
        "pad_token_id": tokenizer.pad_token_id,
    }
    if eos_ids:
        gen_kwargs["eos_token_id"] = eos_ids if len(eos_ids) > 1 else eos_ids[0]

    with torch.inference_mode():
        input_ids = input_ids.to(device)
        attention_mask = attention_mask.to(device)
        outputs = model.generate(
            input_ids=input_ids,
            attention_mask=attention_mask,
            **gen_kwargs
        )

    # Slice generated continuation
    cont = outputs[0, input_ids.shape[1]:]
    text = tokenizer.decode(cont, skip_special_tokens=True)

    match = _prefix_match(text, eligible_tokens)
    if match is not None:
        return match

    raise TokenNotFoundException(f"Predicted token not in eligible tokens: {text!r}", text)


def _truncate_to_max_src(input_ids: torch.Tensor, attention_mask: torch.Tensor, max_src_length: int):
    if input_ids.size(1) > max_src_length:
        input_ids = input_ids[:, -max_src_length:]
        attention_mask = attention_mask[:, -max_src_length:]
    return input_ids, attention_mask


# ---------------- Public API ---------------- #

def predict_next_token(
    device,
    filename: str,
    model: PreTrainedModel,
    tokenizer: PreTrainedTokenizer,
    max_src_length: int,
    max_tgt_length: int
) -> str:
    # Read datapoint
    with open(filename, 'r') as f:
        tokens_datapoint = json.load(f)

    # Prepare chat-formatted inputs
    system_text, user_text, assistant_prefix, eligible_tokens = process_dataset(tokens_datapoint)
    input_ids, attention_mask, im_end_id = _qwen_chat_ids_for_inference(
        tokenizer, system_text, user_text, assistant_prefix
    )
    input_ids, attention_mask = _truncate_to_max_src(input_ids, attention_mask, int(max_src_length))

    # Generate next token (as a string from eligible_tokens)
    next_token = infer(
        device=device,
        model=model,
        input_ids=input_ids,
        attention_mask=attention_mask,
        tokenizer=tokenizer,
        eligible_tokens=eligible_tokens,
        max_tgt_length=int(max_tgt_length),
        extra_eos_id=im_end_id
    )

    # Control-flow identical to your original logic
    if tokens_datapoint.get('oracleSoFar', '') == '':
        if next_token == no_assertion_token:
            return ";"
        elif next_token == assert_token:
            tokens_datapoint['oracleSoFar'] = "assertTrue("
            with open(filename, 'w') as json_file:
                json.dump(tokens_datapoint, json_file, indent=4)
            return predict_next_token(
                device, filename, model, tokenizer, max_src_length, max_tgt_length
            )
        else:
            raise Exception(f"Predicted token not in eligible tokens: {next_token}")
    else:
        return next_token if next_token != ");" else ";"
