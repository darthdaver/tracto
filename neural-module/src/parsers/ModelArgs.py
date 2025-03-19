from dataclasses import dataclass, field

@dataclass
class ModelArgs:
    """
    This helper class collects all model-related arguments.
    """
    model_name_or_path: str = field(
        metadata={"help": "Path to pretrained model, checkpoint, or model identifier from huggingface.co/models"}
    )
    tokenizer_name: str = field(
        metadata={"help": "Pretrained tokenizer name or path."}
    )