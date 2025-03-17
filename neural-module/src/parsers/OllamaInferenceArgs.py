from dataclasses import dataclass, field
from typing import Optional


@dataclass
class OllamaInferenceArgs:
    dataset_path: str = field(
        metadata={"help": "Path to the dataset file or folder (if the dataset is split in multiple files."}
    )
    output_path: str = field(
        metadata={"help": "Output path where to save the results of the model."}
    )
    query_path: Optional[str] = field(
        default=None,
        metadata={"help": "Path to the query request template file."}
    )