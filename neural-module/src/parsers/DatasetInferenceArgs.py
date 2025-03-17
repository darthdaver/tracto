from dataclasses import dataclass, field
from typing import Optional


@dataclass
class DatasetInferenceArgs:
    dataset_path: str = field(
        metadata={"help": "Path to the dataset file or folder (if the dataset is split in multiple files."}
    )
    split: str = field(
        metadata={"help": "The split of the data to use (train, validation, or test)."}
    )
    src_col: str = field(
        metadata={"help": "The name of the column in the datasets containing the input."}
    )
    tgt_col: str = field(
        metadata={"help": "The name of the column in the datasets containing the target."}
    )
    max_src_len: int = field(
        metadata={"help": "The maximum total input sequence length after tokenization. Sequences exceeding the length will be truncated, otherwise they will be padded."}
    )
    max_tgt_len: int = field(
        metadata={"help": "The maximum total sequence length for target text after tokenization. Sequences exceeding the length will be truncated, otherwise they will be padded."}
    )
    batch_size: int = field(
        metadata={"help": "Batch size used for inference."}
    )
    output_path: str = field(
        metadata={"help": "Output path where to save the results of the model."}
    )
    num_beams: Optional[int] = field(
        default=1,
        metadata={"help": "Number of beams to use for evaluation."}
    )
    query_path: Optional[str] = field(
        default=None,
        metadata={"help": "Path to the query request template file."}
    )