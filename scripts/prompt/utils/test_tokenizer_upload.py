from transformers import AutoTokenizer

# Carica il tokenizer
tokenizer_path = "/Users/davidemolinelli/Downloads/tracto/scripts/neural-module/output/Qwen2.5-Coder-1.5B/1" #"/path/to/your/saved/tokenizer"
tok = AutoTokenizer.from_pretrained(tokenizer_path, trust_remote_code=True)

# Test 1: Verifica special tokens
print("Special tokens map:")
print(tok.special_tokens_map)

# Test 2: Verifica additional_special_tokens
print("\nAdditional special tokens:")
print(tok.additional_special_tokens)

# Test 3: Prova a encodare i FIM tokens
fim_tokens = ["<|fim_prefix|>", "<|fim_middle|>", "<|fim_suffix|>"]
for token in fim_tokens:
    ids = tok.encode(token, add_special_tokens=False)
    print(f"{token}: {ids} (len={len(ids)})")

    # Check in added_tokens_encoder
    if hasattr(tok, 'added_tokens_encoder'):
        if token in tok.added_tokens_encoder:
            print(f"  -> Found in added_tokens_encoder: {tok.added_tokens_encoder[token]}")

    # Check in added_tokens_decoder
    if hasattr(tok, 'added_tokens_decoder'):
        for idx, added in tok.added_tokens_decoder.items():
            token_str = str(added) if not isinstance(added, str) else added
            if hasattr(added, 'content'):
                token_str = added.content
            if token_str == token:
                print(f"  -> Found in added_tokens_decoder at index: {idx}")
                break

# Test 4: Verifica vocab size
print(f"\nVocab size: {len(tok)}")
print(f"Model max length: {tok.model_max_length}")
