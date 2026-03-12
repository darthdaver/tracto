import os
from dataclasses import dataclass, field

@dataclass
class EwashArgsParser:
    input:str = field(
        metadata={"help": "The path to the input file or directory (containing the files to process)."}
    )
    output:str = field(
        metadata={"help": "The path to the output directory (where to save the processed files)."}
    )
    config:str = field(
        default=os.path.join(os.path.abspath(os.path.dirname(__file__)), '..', 'resources', 'ewash_config.json'),
        metadata={"help": "The path to the configuration file for the E-WASH approach."}
    )
    intros:str = field(
        default=os.path.join(os.path.abspath(os.path.dirname(__file__)), '..', 'resources', 'ewash_intros.json'),
        metadata={"help": "The path to the dictionary of intro comments for each section of the input for the E-WASH approach."}
    )
    phase: str = field(
        default="choice",
        metadata={"help": "Phase of the model to be used: 'choice' or 'emit'."}
    )
    literal: str = field(
        default="STRING_LITERAL",
        metadata={"help": "Phase of the model to be used: 'choice' or 'emit'."}
    )
    rag_config: str = field(
        default=os.path.join(os.path.abspath(os.path.dirname(__file__)), '../rag/config.json'),
        metadata={"help": "Path to RAG configuration file"}
    )
    rag_enabled: bool = field(
        default=True,
        metadata={"help": "Enable/disable RAG context integration"}
    )
    rag_max_snippets: int = field(
        default=5,
        metadata={"help": "Maximum number of snippets to retrieve with RAG"}
    )
    rag_threshold: float = field(
        default=0.7,
        metadata={"help": "Minimum similarity score for RAG retrieval (0-1)"}
    )