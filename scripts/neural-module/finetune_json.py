#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Fine-tuning script with Trie/WFSA-based Constrained Decoding for Oracle Token-by-Token Generation.
Supports both training-time and inference-time constraint enforcement using efficient trie structures.
"""

import time, json, logging, glob, bisect, re
from dataclasses import dataclass, field
from typing import Dict, Optional, List, Tuple, Set, Iterator, Any
from types import MappingProxyType
from torch.utils.data import Sampler
from huggingface_hub import snapshot_download
from pathlib import Path
import random
import os, multiprocessing as mp

os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")
os.environ.setdefault("HF_DATASETS_DISABLE_PARALLELISM", "1")

try:
    mp.set_start_method("spawn", force=True)
except RuntimeError:
    pass

import numpy as np
import torch, torch.distributed as dist

torch.backends.cuda.matmul.allow_tf32 = True
torch.backends.cudnn.allow_tf32 = True

from torch.utils.data import Dataset
import transformers
from transformers import Trainer, LogitsProcessor, AutoTokenizer
from transformers import BitsAndBytesConfig
from torch.utils.data import DataLoader
from peft.utils import prepare_model_for_kbit_training
from peft import get_peft_model, LoraConfig

IGNORE_INDEX = -100

# Sample queries for generation testing
sample_query_1 = """<|fim_prefix|>
// Test prefix
public void testToJson() throws IOException {
    List<WorkflowRuleTarget> targets = Collections.singletonList(new WorkflowRuleTarget.Builder("WQaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").priority(54333).timeout(30).build());
    List<WorkflowRule> rules = Collections.singletonList(new WorkflowRule.Builder("skill == \"HR\"", targets).friendlyName("4354").build());
    WorkflowRuleTarget defaultTarget = new WorkflowRuleTarget.Builder("WQaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").build();
    Workflow workflow = new Workflow(rules, defaultTarget);
    assertEquals(<|fim_suffix|>

# The program uses Junit Version: JUnit 4

# NEXT TOKEN CANDIDATES
# PROJECT TOKENS: ["WorkflowRuleTargetTestSTARSplitTest", "TaskRouterResource", "WorkflowTestSTARSplitTestPrefix", "WorkflowRuleTarget", "Workflow", "IOException", "TaskRouting", "WorkflowTestSTARNormalizedTest", "WorkflowRuleTargetTestSTARSplitTestPrefix", "WorkflowRuleTargetTestSTARNormalizedTest", "Assert", "WorkflowTestSTARSplitTest", "WorkflowRule", "Collections", "WorkflowRuleTargetTest", "ObjectMapper", "WorkflowTest", "getClass", "wait", "hashCode", "notifyAll", "equals", "clone", "toString", "finalize", "registerNatives", "notify", "defaultTarget", "rules", "workflow", "targets"]
# JAVA TOKENS: ["-", "@", "boolean", "byte", "char", "double", "float", "int", "long", "new", "short", "super", "switch", "this", "void", "module", "open", "requires", "exports", "opens", "to", "uses", "provides", "with", "transitive", "var", "yield", "record", "sealed", "permits", "true", "false", "TEXT_BLOCK", "null", "(", "<", "!", "~", "++", "--", "+"]
# GENERIC TOKENS: ["DECIMAL_LITERAL", "HEX_LITERAL", "OCT_LITERAL", "BINARY_LITERAL", "FLOAT_LITERAL", "HEX_FLOAT_LITERAL", "CHAR_LITERAL", "STRING_LITERAL"]

# TASK: Choose exactly one among the three given list as the NEXT token/type.
# OUTPUT: Generate a JSON on one line: {"choice":"<candidate>"}

# ADDITIONAL CONTEXTUAL INFORMATION (CAN GUIDE THE CHOICE):
// Invoked methods in test prefix
/**
 * Returns an immutable list containing only the specified object.
 * The returned list is serializable.
 *
 * @param  <T> the class of the objects in the list
 * @param o the sole object to be stored in the returned list.
 * @return an immutable list containing only the specified object.
 * @since 1.3
 */
public static <T> List<T> singletonList(T o){
    return new SingletonList<>(o);
}

public WorkflowRuleTarget build(){
    return new WorkflowRuleTarget(this);
}

public Builder timeout(Integer timeout){
    this.timeout = timeout;
    return this;
}

public Builder priority(Integer priority){
    this.priority = priority;
    return this;
}

public Builder(String queue){
    this.queue = queue;
}

public WorkflowRule build(){
    return new WorkflowRule(this);
}

public Builder friendlyName(String friendlyName){
    this.friendlyName = friendlyName;
    return this;
}

public Builder(String expression, List<WorkflowRuleTarget> targets){
    this.expression = expression;
    this.targets = targets;
}

/**
 * Define a workflow.
 *
 * @param workflowRules list of workflow rules (in order they will be processed)
 * @param defaultTarget default filter
 */
public Workflow(List<WorkflowRule> workflowRules, WorkflowRuleTarget defaultTarget){
    taskRouting = new TaskRouting(workflowRules, defaultTarget);
}

// Constructors and methods defined in focal class
public List<WorkflowRule> getWorkflowRules(){
    return taskRouting.getWorkflowRules();
}

public WorkflowRuleTarget getDefaultTarget(){
    return taskRouting.getDefaultTarget();
}

/**
 * Converts a JSON workflow configuration to a workflow object.
 *
 * @param json JSON for workflow
 * @return a workflow rule target object
 * @throws IOException if unable to create object
 */
public static Workflow fromJson(String json) throws IOException{
    ObjectMapper mapper = new ObjectMapper();
    return mapper.readValue(json, Workflow.class);
}

// Fields defined in focal class
private final TaskRouting taskRouting;

<|fim_middle|>"""

sample_query_2 = """<|fim_prefix|>
// Test prefix
public void testFromJson() throws IOException {
    WorkflowRuleTarget target = WorkflowRuleTarget.fromJson("{\"queue\":\"QS123\",\"expression\":\"1==1\",\"priority\":5,\"timeout\":30,\"order_by\":\"worker.english_level ASC\",\"skip_if\":\"workers.available == 0\",\"known_worker_friendly_name\":\"task.requested_agent\",\"known_worker_sid\":\"task.requested_agent_id\"}");
    assertEquals("QS123", target.getQueue());
    assertEquals(<|fim_suffix|>

# The program uses Junit Version: JUnit 4

# EMIT a Java STRING_LITERAL as the next token
# OUTPUT ONLY the literal including quotes, with valid Java escapes. Nothing else.

# ADDITIONAL CONTEXTUAL INFORMATION (CAN HELP THE GENERATION):
// Invoked methods in test prefix
/**
 * Converts a JSON workflow configuration to a workflow configuration object.
 *
 * @param json JSON for workflow rule target
 * @return a workflow rule target object
 * @throws IOException if unable to create object
 */
public static WorkflowRuleTarget fromJson(String json) throws IOException{
    ObjectMapper mapper = new ObjectMapper();
    return mapper.readValue(json, WorkflowRuleTarget.class);
}

/**
 * Get the queue for the workflow rule target.
 *
 * @return queue sid
 */
public String getQueue(){
    return queue;
}

// Constructors and methods defined in focal class
/**
 * Get the queue for the workflow rule target.
 *
 * @return queue sid
 */
public String getQueue(){
    return queue;
}

/**
 * Get the expression for the workflow rule target to limit the workers selected.
 *
 * @return the expression
 */
public String getExpression(){
    return expression;
}

/**
 * Get the priority for the workflow rule target.
 *
 * @return the priority
 */
public Integer getPriority(){
    return priority;
}

/**
 * Get the timeout for the workflow rule target.
 *
 * @return the timeout
 */
public Integer getTimeout(){
    return timeout;
}

/**
 * Get the orderBy for the workflow rule target.
 *
 * @return the orderBy
 */
public String getOrderBy(){
    return orderBy;
}

/**
 * Get the skipIf for the workflow rule target.
 *
 * @return the skipIf
 */
public String getSkipIf(){
    return skipIf;
}

/**
 * Get the knownWorkerSid expression for the workflow rule target.
 *
 * @return the knownWorkerSid expression
 */
public String getKnownWorkerSid(){
    return knownWorkerSid;
}

/**
 * Get the knownWorkerFriendlyName expression for the workflow rule target.
 *
 * @return the knownWorkerFriendlyName expression
 */
public String getKnownWorkerFriendlyName(){
    return knownWorkerFriendlyName;
}

/**
 * Converts a JSON workflow configuration to a workflow configuration object.
 *
 * @param json JSON for workflow rule target
 * @return a workflow rule target object
 * @throws IOException if unable to create object
 */
public static WorkflowRuleTarget fromJson(String json) throws IOException{
    ObjectMapper mapper = new ObjectMapper();
    return mapper.readValue(json, WorkflowRuleTarget.class);
}

// Fields defined in focal class
private final String queue;

private final String expression;

private final Integer priority;

private final Integer timeout;

private final String orderBy;

private final String skipIf;

private final String knownWorkerSid;

private final String knownWorkerFriendlyName;

private String queue;

private String expression;

private Integer priority;

private Integer timeout;

private String orderBy;

private String skipIf;

private String knownWorkerSid;

private String knownWorkerFriendlyName;

<|fim_middle|>"""

sample_query_3 = """<|fim_prefix|>
// Test prefix
public void testFromJson() throws IOException {
    WorkflowRuleTarget target = WorkflowRuleTarget.fromJson("{\"queue\":\"QS123\",\"expression\":\"1==1\",\"priority\":5,\"timeout\":30,\"order_by\":\"worker.english_level ASC\",\"skip_if\":\"workers.available == 0\",\"known_worker_friendly_name\":\"task.requested_agent\",\"known_worker_sid\":\"task.requested_agent_id\"}");
    assertEquals("QS123", target.getQueue());
    assertEquals("1==1", target.getExpression());
    assertEquals(5,(int )<|fim_suffix|>

# The program uses Junit Version: JUnit 4

# NEXT TOKEN CANDIDATES
# PROJECT TOKENS: ["WorkflowRuleTargetTestSTARSplitTest", "TaskRouterResource", "WorkflowTestSTARSplitTestPrefix", "WorkflowRuleTarget", "Workflow", "IOException", "TaskRouting", "WorkflowTestSTARNormalizedTest", "WorkflowRuleTargetTestSTARSplitTestPrefix", "WorkflowRuleTargetTestSTARNormalizedTest", "Assert", "WorkflowTestSTARSplitTest", "WorkflowRule", "WorkflowRuleTargetTest", "ObjectMapper", "WorkflowTest", "getClass", "wait", "hashCode", "notifyAll", "equals", "clone", "toString", "registerNatives", "finalize", "notify", "target"]
# JAVA TOKENS: ["-", "@", "boolean", "byte", "char", "double", "float", "int", "long", "new", "short", "super", "switch", "this", "void", "module", "open", "requires", "exports", "opens", "to", "uses", "provides", "with", "transitive", "var", "yield", "record", "sealed", "permits", "true", "false", "TEXT_BLOCK", "null", "(", "<", "!", "~", "++", "--", "+"]
# GENERIC TOKENS: ["DECIMAL_LITERAL", "HEX_LITERAL", "OCT_LITERAL", "BINARY_LITERAL", "FLOAT_LITERAL", "HEX_FLOAT_LITERAL", "CHAR_LITERAL", "STRING_LITERAL"]

# TASK: Choose exactly one among the three given list as the NEXT token/type.
# OUTPUT: Generate a JSON on one line: {"choice":"<candidate>"}

# ADDITIONAL CONTEXTUAL INFORMATION (CAN GUIDE THE CHOICE):
// Invoked methods in test prefix
/**
 * Converts a JSON workflow configuration to a workflow configuration object.
 *
 * @param json JSON for workflow rule target
 * @return a workflow rule target object
 * @throws IOException if unable to create object
 */
public static WorkflowRuleTarget fromJson(String json) throws IOException{
    ObjectMapper mapper = new ObjectMapper();
    return mapper.readValue(json, WorkflowRuleTarget.class);
}

/**
 * Get the queue for the workflow rule target.
 *
 * @return queue sid
 */
public String getQueue(){
    return queue;
}

/**
 * Get the expression for the workflow rule target to limit the workers selected.
 *
 * @return the expression
 */
public String getExpression(){
    return expression;
}

// Constructors and methods defined in focal class
/**
 * Get the queue for the workflow rule target.
 *
 * @return queue sid
 */
public String getQueue(){
    return queue;
}

/**
 * Get the expression for the workflow rule target to limit the workers selected.
 *
 * @return the expression
 */
public String getExpression(){
    return expression;
}

/**
 * Get the priority for the workflow rule target.
 *
 * @return the priority
 */
public Integer getPriority(){
    return priority;
}

/**
 * Get the timeout for the workflow rule target.
 *
 * @return the timeout
 */
public Integer getTimeout(){
    return timeout;
}

/**
 * Get the orderBy for the workflow rule target.
 *
 * @return the orderBy
 */
public String getOrderBy(){
    return orderBy;
}

/**
 * Get the skipIf for the workflow rule target.
 *
 * @return the skipIf
 */
public String getSkipIf(){
    return skipIf;
}

/**
 * Get the knownWorkerSid expression for the workflow rule target.
 *
 * @return the knownWorkerSid expression
 */
public String getKnownWorkerSid(){
    return knownWorkerSid;
}

/**
 * Get the knownWorkerFriendlyName expression for the workflow rule target.
 *
 * @return the knownWorkerFriendlyName expression
 */
public String getKnownWorkerFriendlyName(){
    return knownWorkerFriendlyName;
}

/**
 * Converts a JSON workflow configuration to a workflow configuration object.
 *
 * @param json JSON for workflow rule target
 * @return a workflow rule target object
 * @throws IOException if unable to create object
 */
public static WorkflowRuleTarget fromJson(String json) throws IOException{
    ObjectMapper mapper = new ObjectMapper();
    return mapper.readValue(json, WorkflowRuleTarget.class);
}

// Fields defined in focal class
private final String queue;

private final String expression;

private final Integer priority;

private final Integer timeout;

private final String orderBy;

private final String skipIf;

private final String knownWorkerSid;

private final String knownWorkerFriendlyName;

private String queue;

private String expression;

private Integer priority;

private Integer timeout;

private String orderBy;

private String skipIf;

private String knownWorkerSid;

private String knownWorkerFriendlyName;

<|fim_middle|>"""

logging.basicConfig(level=logging.INFO)

STAGE_PATTERNS = MappingProxyType({
    "selection": re.compile(r'#\s*TASK:\s*Choose exactly one.*?#\s*OUTPUT:.*?$', re.MULTILINE | re.DOTALL),
    "generation": re.compile(r'#\s*EMIT a.*?#\s*OUTPUT:.*?$', re.MULTILINE | re.DOTALL)
})


# -------------------------
# Arguments
# -------------------------

@dataclass
class ModelArguments:
    model_name_or_path: Optional[str] = field(
        default="Qwen/Qwen2.5-Coder-14B",
        metadata={"help": "HF model name or absolute path to local base model folder."},
    )
    model_revision: Optional[str] = field(
        default=None,
        metadata={"help": "HF revision (commit hash or tag) of the base model to pin."}
    )
    start_id: Optional[str] = field(
        default="<|im_start|>",
        metadata={"help": "Opening token for a message block (fallback formatter)."}
    )
    end_id: Optional[str] = field(
        default="<|im_end|>",
        metadata={"help": "Closing token for a message block (fallback formatter)."}
    )
    use_flash_attention: bool = field(
        default=False,
        metadata={"help": "Whether to use Flash Attention."}
    )


@dataclass
class DataArguments:
    tokenized_dir: str = field(
        default=None,
        metadata={"help": "Folder with pre-tokenized shards: train.*.{bin,idx}, val.*.{bin,idx}, and meta JSONs."}
    )


@dataclass
class TrainingArguments(transformers.TrainingArguments):
    output_dir: Optional[str] = field(
        default=os.path.join(os.path.dirname(__file__), "output", "ft"),
        metadata={"help": "Output directory for checkpoints and training outputs."}
    )
    cache_dir: Optional[str] = field(default=None)
    optim: str = field(default="adamw_torch")
    model_max_length: int = field(default=8192)
    num_train_epochs: int = field(default=3)
    use_peft: bool = field(default=True)
    deepspeed: Optional[str] = None
    dataloader_num_workers: int = 8
    dataloader_persistent_workers: bool = True
    dataloader_pin_memory: bool = True
    gradient_checkpointing: bool = True
    bf16: bool = True
    fp16: bool = False
    per_device_train_batch_size: int = 1
    per_device_eval_batch_size: int = 1
    gradient_accumulation_steps: int = 16
    group_by_length: bool = False
    ddp_find_unused_parameters: bool = False
    eval_accumulation_steps: int = 1
    prediction_loss_only: bool = True
    max_grad_norm: float = 0.3
    report_to: Optional[str] = "none"
    torch_compile: bool = False
    remove_unused_columns: bool = False
    include_num_input_tokens_seen: bool = False
    min_early_stopping_epochs: int = 1
    early_stopping_patience: int = 2
    seed: int = 42
    # constraint_weight: float = field(default=0.1, metadata={"help": "Weight for constraint loss"})
    # enable_training_constraints: bool = field(default=True, metadata={"help": "Enable constraints during training"})


# -------------------------
# Trie-Based Constrained Decoding
# -------------------------

class TrieNode:
    """Efficient trie node for constraint sequences"""

    def __init__(self):
        self.children: Dict[int, 'TrieNode'] = {}
        self.is_terminal = False
        self.depth = 0


class ConstraintTrie:
    """Simplified trie structure for complete constraint sequences only (Option 1)"""

    def __init__(self, constraint_sequences: List[List[int]], processing_class: Optional[AutoTokenizer] = None,
                 eos_token_id: Optional[int] = None):
        self.root = TrieNode()
        self.eos_token_id = eos_token_id
        self.processing_class = processing_class
        self._build_simple_trie(constraint_sequences)

    def _build_simple_trie(self, sequences: List[List[int]]):
        """Build trie from complete constraint sequences only (no sub-sequences)"""
        logging.info(f"\n[DEBUG] Building simplified ConstraintTrie with {len(sequences)} sequences...")

        for seq_idx, seq in enumerate(sequences):
            if not seq:  # Skip empty sequences
                continue
            # logging.info(f"  Sequence {seq_idx + 1}: {seq}")
            # if self.processing_class:
            #     logging.info(f"  Decoded: '{[self.processing_class.decode(s, skip_special_tokens=False) for s in seq]}'")
            # Add the complete sequence to trie (no sub-sequences)
            current = self.root
            for depth, token_id in enumerate(seq):
                if token_id not in current.children:
                    current.children[token_id] = TrieNode()
                    current.children[token_id].depth = depth + 1
                current = current.children[token_id]

            # Mark the final node as terminal
            current.is_terminal = True

    def get_valid_continuations(self, prefix: List[int]) -> Set[int]:
        """Get valid token IDs that can continue the given prefix"""
        current = self.root

        # Navigate to the current prefix position in the trie
        for token_id in prefix:
            if token_id not in current.children:
                logging.debug(f"[Trie] Invalid prefix at token {token_id}")
                return set()  # Invalid prefix
            current = current.children[token_id]

        # Return all valid next tokens from this position
        valid_tokens = set(current.children.keys())

        # Add EOS if we're at a terminal node
        if current.is_terminal and self.eos_token_id is not None:
            valid_tokens.add(self.eos_token_id)

        # Debug logging
        if self.processing_class and prefix:
            decoded_prefix = self.processing_class.decode(prefix, skip_special_tokens=False)
            logging.debug(f"[Trie] Prefix: {prefix} → '{decoded_prefix}'")
        else:
            logging.debug(f"[Trie] Prefix: {prefix} (empty)")

        logging.debug(f"[Trie] Valid continuations: {len(valid_tokens)} tokens")
        if self.processing_class and valid_tokens:
            sample_decoded = [self.processing_class.decode([t], skip_special_tokens=False)
                              for t in list(valid_tokens)[:3]]
            logging.debug(f"[Trie] Sample continuations: {sample_decoded}")

        return valid_tokens

    def is_prefix_valid(self, prefix: List[int]) -> bool:
        """Check if prefix is valid in trie"""
        current = self.root
        for token_id in prefix:
            if token_id not in current.children:
                return False
            current = current.children[token_id]
        return True


class AdvancedConstrainedLogitsProcessor(LogitsProcessor):
    """
    Simplified constrained logits processor working directly with encoded sequences.
    """

    def __init__(self, tokenizer, constraint_sequences: List[List[int]],
                 stage: str = "selection", enable_training_constraints: bool = True):
        self.processing_class = tokenizer
        self.stage = stage
        self.enable_training_constraints = enable_training_constraints

        # Build constraint trie directly from encoded sequences
        if constraint_sequences:
            self.constraint_trie = ConstraintTrie(
                constraint_sequences=constraint_sequences,
                processing_class=tokenizer,
                eos_token_id=tokenizer.eos_token_id
            )
        else:
            self.constraint_trie = None

        self.call_count = 0

    def __call__(self, input_ids: torch.Tensor, scores: torch.Tensor) -> torch.Tensor:
        """Apply simplified constraints - only complete sequence continuations allowed."""
        self.call_count += 1

        # Skip constraints if disabled or no trie
        if not self.enable_training_constraints or self.constraint_trie is None:
            return scores

        # Get current context
        current_text = self.processing_class.decode(input_ids[0], skip_special_tokens=False)
        current_stage = self._detect_generation_stage(current_text)

        logging.debug(f"[DEBUG] Current stage: {current_stage}")

        # Skip constraints for generation stage (free text generation)
        if current_stage == "generation":
            return scores

        # Extract JSON context for constraint checking
        json_context = self._extract_current_json_context(current_text)

        # Get valid tokens from simplified trie
        valid_tokens = self.constraint_trie.get_valid_continuations(json_context)

        # Debug logging
        if self.call_count <= 10:
            decoded_context = self.processing_class.decode(json_context, skip_special_tokens=False)
            logging.info(f"🔍 Simplified Constraint Call #{self.call_count}:")
            logging.info(f"   Context: '{decoded_context}' (tokens: {json_context})")
            logging.info(f"   Valid continuations: {len(valid_tokens)}")
            if valid_tokens:
                decoded_allowed = [self.processing_class.decode([t], skip_special_tokens=False)
                                   for t in list(valid_tokens)[:5]]
                logging.info(f"   Sample allowed: {decoded_allowed}")
            else:
                logging.info(f"   No valid continuations found")

        # If no valid tokens, return scores unchanged
        if not valid_tokens:
            logging.warning(f"⚠️ No valid continuations at call #{self.call_count}")
            logging.warning(f"   This will be handled by token substitution in trainer")
            return scores

        # Apply constraints - only allow valid tokens
        constraint_mask = torch.full_like(scores, float('-inf'))
        valid_count = 0

        for token_id in valid_tokens:
            if 0 <= token_id < scores.shape[-1]:
                constraint_mask[:, token_id] = 0.0
                valid_count += 1

        # Safety check
        if valid_count == 0:
            logging.error(f"❌ No valid tokens in vocabulary range, returning unconstrained")
            return scores

        return scores + constraint_mask

    def _extract_current_json_context(self, text: str) -> List[int]:
        """Extract current generated tokens for constraint checking."""
        fim_middle_marker = "<|fim_middle|>"
        if fim_middle_marker not in text:
            return []

        generated_text = text.split(fim_middle_marker)[-1]
        if not generated_text:
            return []

        generated_tokens = self.processing_class.encode(generated_text, add_special_tokens=False)
        return generated_tokens

    def _detect_generation_stage(self, text: str) -> str:
        """Detect which stage we're in."""
        if "# EMIT a" in text:
            return "generation"
        elif "# TASK: Choose exactly one" in text:
            return "selection"
        else:
            return self.stage


# -------------------------
# Enhanced Dataset with Constraint Support - FIXED VERSION
# -------------------------

class ConstrainedJSONDataset(Dataset):
    """
    Memory-efficient streaming dataset that loads JSON files on-demand.
    Prevents RAM overflow by processing files individually during training.
    """

    def __init__(
            self,
            root: str,
            split: str,
            shuffle: bool = True,
            seed: int = 42,
            max_samples_per_epoch: Optional[int] = None,
            length_sort_buffer_size: int = 1000
    ):
        """
        Initialize streaming dataset.

        Args:
            root: Directory containing JSON files
            split: Split name (train/val)
            shuffle: Whether to shuffle files and samples
            seed: Random seed for shuffling
            max_samples_per_epoch: Limit samples per epoch (None = use all)
            length_sort_buffer_size: Buffer size for length-based sorting
        """
        self.root = root
        self.split = split
        self.shuffle = shuffle
        self.seed = seed
        self.max_samples_per_epoch = max_samples_per_epoch
        self.length_sort_buffer_size = length_sort_buffer_size
        self.epoch = 0

        # Find all JSON files for this split
        self.json_files = self._discover_json_files()

        # Load metadata for total sample count
        self.total_samples, self.estimated_lengths = self._load_metadata()

        logging.info(
            f"StreamingDataset initialized: {len(self.json_files)} files, "
            f"~{self.total_samples} samples for split '{split}'"
        )

    def _discover_json_files(self) -> List[str]:
        """Find all JSON files for the split."""
        pattern = os.path.join(self.root, f"{self.split}.*.json")
        json_files = sorted(glob.glob(pattern))

        # Exclude summary files
        json_files = [f for f in json_files if not f.endswith('.summary.json')]

        if not json_files:
            raise FileNotFoundError(f"No {self.split} JSON files found in {self.root}")

        return json_files

    def _load_metadata(self) -> tuple[int, List[int]]:
        """Load metadata about total samples and length distribution."""
        total_samples = 0
        all_lengths = []

        # Try loading from summary file first
        summary_path = os.path.join(self.root, f"{self.split}.summary.json")
        if os.path.exists(summary_path):
            try:
                with open(summary_path, 'r') as f:
                    summary = json.load(f)
                return summary.get('total_samples', 0), []
            except Exception as e:
                logging.warning(f"Could not load summary file: {e}")

        # Fallback: scan first few files to estimate
        sample_files = self.json_files[:min(3, len(self.json_files))]

        for json_file in sample_files:
            try:
                with open(json_file, 'r') as f:
                    data = json.load(f)

                if 'metadata' in data and 'num_samples' in data['metadata']:
                    file_samples = data['metadata']['num_samples']
                else:
                    samples = data.get('samples', data if isinstance(data, list) else [data])
                    file_samples = len(samples)

                    # Collect length info from first file
                    if json_file == sample_files[0]:
                        all_lengths.extend([
                            sample.get('length', len(sample.get('input_ids', [])))
                            for sample in samples[:100]  # Sample first 100
                        ])

                total_samples += file_samples

            except Exception as e:
                logging.warning(f"Error reading metadata from {json_file}: {e}")

        # Extrapolate to all files
        if sample_files:
            avg_samples_per_file = total_samples / len(sample_files)
            estimated_total = int(avg_samples_per_file * len(self.json_files))
        else:
            estimated_total = 0

        return estimated_total, all_lengths

    def set_epoch(self, epoch: int):
        """Set epoch for deterministic shuffling."""
        self.epoch = epoch

    def __iter__(self) -> Iterator[Dict[str, torch.Tensor]]:
        """Stream samples from JSON files."""
        # Set up random state for this epoch
        rng = random.Random(self.seed + self.epoch)

        # Shuffle file order if requested
        files_to_process = list(self.json_files)
        if self.shuffle:
            rng.shuffle(files_to_process)

        samples_yielded = 0
        length_sort_buffer = []

        for json_file in files_to_process:
            # Check if we've reached the sample limit
            if (self.max_samples_per_epoch is not None and
                    samples_yielded >= self.max_samples_per_epoch):
                break

            try:
                # Load file samples
                file_samples = self._load_file_samples(json_file)

                # Shuffle samples within file if requested
                if self.shuffle:
                    rng.shuffle(file_samples)

                # Process samples with optional length sorting
                for sample in file_samples:
                    if (self.max_samples_per_epoch is not None and
                            samples_yielded >= self.max_samples_per_epoch):
                        break

                    # Add to length sort buffer if enabled
                    if self.length_sort_buffer_size > 1:
                        length_sort_buffer.append(sample)

                        # Yield from buffer when full
                        if len(length_sort_buffer) >= self.length_sort_buffer_size:
                            yield from self._yield_sorted_buffer(length_sort_buffer, rng)
                            samples_yielded += len(length_sort_buffer)
                            length_sort_buffer = []
                    else:
                        # Direct yield without buffering
                        processed_sample = self._process_sample(sample)
                        if processed_sample is not None:
                            yield processed_sample
                            samples_yielded += 1

            except Exception as e:
                logging.error(f"Error processing {json_file}: {e}")
                continue

        # Yield remaining buffered samples
        if length_sort_buffer:
            yield from self._yield_sorted_buffer(length_sort_buffer, rng)

    def _load_file_samples(self, json_file: str) -> List[Dict[str, Any]]:
        """Load samples from a single JSON file."""
        with open(json_file, 'r', encoding='utf-8') as f:
            data = json.load(f)

        # Extract samples from JSON structure
        if 'samples' in data:
            return data['samples']
        elif isinstance(data, list):
            return data
        else:
            return [data]

    def _yield_sorted_buffer(self, buffer: List[Dict[str, Any]], rng: random.Random) -> Iterator[
        Dict[str, torch.Tensor]]:
        """Sort buffer by length and yield samples."""
        # Sort by length (longest first for better batching)
        buffer.sort(key=lambda x: x.get('length', len(x.get('input_ids', []))), reverse=True)

        # Add some randomness to avoid overly rigid sorting
        if self.shuffle and len(buffer) > 10:
            # Shuffle within length buckets
            bucket_size = len(buffer) // 4
            for i in range(0, len(buffer), bucket_size):
                end_idx = min(i + bucket_size, len(buffer))
                bucket = buffer[i:end_idx]
                rng.shuffle(bucket)
                buffer[i:end_idx] = bucket

        # Yield processed samples
        for sample in buffer:
            processed_sample = self._process_sample(sample)
            if processed_sample is not None:
                yield processed_sample

    def _process_sample(self, sample: Dict[str, Any]) -> Optional[Dict[str, torch.Tensor]]:
        """Convert raw sample to tensors."""
        try:
            # Validate required fields
            if 'input_ids' not in sample or 'labels' not in sample:
                return None

            # Convert to tensors
            input_ids = torch.tensor(sample['input_ids'], dtype=torch.long)
            labels = torch.tensor(sample['labels'], dtype=torch.long)
            constraint_sequences = sample.get('constraints', [])

            return {
                "input_ids": input_ids,
                "labels": labels,
                "constraint_sequences": constraint_sequences,
            }

        except Exception as e:
            logging.warning(f"Error processing sample: {e}")
            return None

    def __len__(self) -> int:
        """Return estimated dataset size."""
        if self.max_samples_per_epoch is not None:
            return min(self.max_samples_per_epoch, self.total_samples)
        return self.total_samples


# -------------------------
# Rest of the classes remain the same (Block Distributed Sampler, etc.)
# -------------------------

class BlockDistributedSampler(Sampler):
    """
    Simplified sampler for streaming datasets that works with DDP.
    Since IterableDataset handles ordering, this mainly manages epoch state.
    """

    def __init__(
            self,
            dataset: ConstrainedJSONDataset,
            batch_size: int,
            world_size: int = 1,
            rank: int = 0,
            seed: int = 42,
    ):
        self.dataset = dataset
        self.batch_size = batch_size
        self.world_size = world_size
        self.rank = rank
        self.seed = seed
        self.epoch = 0

        # Calculate samples per rank
        total_samples = len(dataset)
        samples_per_rank = total_samples // world_size
        self._len = (samples_per_rank // batch_size) * batch_size

    def set_epoch(self, epoch: int):
        """Set epoch for dataset."""
        self.epoch = epoch
        self.dataset.set_epoch(epoch)

    def __len__(self) -> int:
        return self._len


# -------------------------
# Tokenizer and Compatibility Functions (unchanged)
# -------------------------

def load_tokenizer_consistent(
        model_name: str,
        model_max_length: int,
        tokenized_dir: str,
        revision: Optional[str] = None,
        special_tokens: List[str] = ["<|im_start|>", "<|im_end|>"]
):
    """
    Loads the tokenizer in the exact same way as the builder did when the dataset has been tokenized and encoded in
    `scripts/prompt`. If a saved tokenizer exists under tokenized_dir/tokenizer/, prefer that. Else load from model
    hub and add the same special tokens.
    """

    # Get the directory with the saved tokenizer, if any
    saved_tok_dir = os.path.join(tokenized_dir, "tokenizer")

    # If the directory exists, load the tokenizer from there
    if os.path.isdir(saved_tok_dir):
        tok = transformers.AutoTokenizer.from_pretrained(saved_tok_dir, trust_remote_code=True)

    # Else load the tokenizer from model hub and add the special tokens
    else:
        tok = transformers.AutoTokenizer.from_pretrained(
            model_name,
            revision=revision,
            trust_remote_code=True,
            padding_side="right"
        )

    if tok.pad_token_id is None:
        tok.pad_token = tok.eos_token

    # Suppress "longer than max length" warnings; we enforce our own cap in data
    tok.model_max_length = 1_000_000_000

    # Add FIM tokens if they don't exist
    fim_tokens = ["<|fim_prefix|>", "<|fim_suffix|>", "<|fim_middle|>"]
    all_tokens = list(special_tokens) + fim_tokens
    unique_tokens = []

    for token in all_tokens:
        if token not in unique_tokens and tok.convert_tokens_to_ids(token) == tok.unk_token_id:
            unique_tokens.append(token)

    if unique_tokens:
        tok.add_special_tokens({"additional_special_tokens": unique_tokens})

    # Set runtime cap for attention mask creation/padding
    tok.model_max_length = model_max_length

    return tok


def assert_meta_compatible(
        tokenized_dir: str,
        tok: transformers.PreTrainedTokenizer,
        special_tokens: List[str],
        split: str
):
    """
    Basic sanity checks between JSON dataset and the runtime tokenizer.
    """
    # Check for summary file first
    summary_path = os.path.join(tokenized_dir, f"{split}.summary.json")
    if os.path.isfile(summary_path):
        with open(summary_path, "r") as f:
            meta = json.load(f)

        logging.info(f"Found summary file for {split}: {meta}")
        return

    # Fall back to checking a sample JSON file
    import glob
    pattern = os.path.join(tokenized_dir, f"{split}.*.json")
    json_files = [f for f in glob.glob(pattern) if not f.endswith('.summary.json')]

    if not json_files:
        logging.warning(f"No {split} JSON files found; skipping tokenizer consistency checks.")
        return

    # Load a sample to validate structure
    try:
        with open(json_files[0], 'r') as f:
            data = json.load(f)

        if 'samples' in data and data['samples']:
            sample = data['samples'][0]
            # Validate that we can decode the sample
            if 'input_ids' in sample:
                decoded = tok.decode(sample['input_ids'], skip_special_tokens=False)
                logging.info(f"Sample validation passed for {split}")

    except Exception as e:
        logging.warning(f"Could not validate sample from {json_files[0]}: {e}")


# -------------------------
# Enhanced Data Collator with Constraint Support
# -------------------------

@dataclass
class ConstraintAwareDataCollator:
    """Data collator that properly handles constraint sequences"""
    processing_class: transformers.PreTrainedTokenizer
    pad_to_multiple_of: int = 8
    static_pad_to: Optional[int] = None

    def __call__(self, instances: List[Dict]) -> Dict[str, torch.Tensor]:
        input_ids = [ex["input_ids"] for ex in instances]
        labels = [ex["labels"] for ex in instances]
        constraint_sequences = [ex.get("constraint_sequences", []) for ex in instances]

        # Standard padding
        ids = torch.nn.utils.rnn.pad_sequence(
            input_ids, batch_first=True, padding_value=self.processing_class.pad_token_id
        )

        lbl = torch.nn.utils.rnn.pad_sequence(
            labels, batch_first=True, padding_value=IGNORE_INDEX
        )

        # Handle padding for target length
        if self.static_pad_to is not None:
            target_len = int(self.static_pad_to)
        else:
            m = int(self.pad_to_multiple_of) if self.pad_to_multiple_of else 1
            target_len = ((ids.size(1) + m - 1) // m) * m

        if ids.size(1) != target_len:
            pad = target_len - ids.size(1)
            if pad < 0:
                ids = ids[:, :target_len]
                lbl = lbl[:, :target_len]
            else:
                ids = torch.nn.functional.pad(ids, (0, pad), value=self.processing_class.pad_token_id)
                lbl = torch.nn.functional.pad(lbl, (0, pad), value=IGNORE_INDEX)

        return {
            "input_ids": ids,
            "labels": lbl,
            "attention_mask": ids.ne(self.processing_class.pad_token_id),
            "constraint_sequences": constraint_sequences,
        }


class StreamingDataLoader:
    """Fixed DataLoader that works with streaming dataset and handles DDP."""

    def __init__(self, dataset, batch_size: int, world_size: int = 1, rank: int = 0,
                 collate_fn=None, drop_last: bool = True):
        self.dataset = dataset
        self.batch_size = batch_size
        self.world_size = world_size
        self.rank = rank
        self.collate_fn = collate_fn
        self.drop_last = drop_last
        self.epoch = 0

    def set_epoch(self, epoch: int):
        """Set epoch for deterministic iteration."""
        self.epoch = epoch
        if hasattr(self.dataset, 'set_epoch'):
            self.dataset.set_epoch(epoch)

    def __iter__(self):
        """Fixed iterator that properly resets each epoch."""
        # Create a fresh dataset iterator for each epoch
        dataset_iter = iter(self.dataset)

        # Skip samples for DDP - simplified logic
        samples_to_skip = self.rank
        for _ in range(samples_to_skip):
            try:
                next(dataset_iter)
            except StopIteration:
                return  # No more data

        batch = []
        samples_collected = 0

        while True:
            try:
                # Collect sample for this rank
                sample = next(dataset_iter)
                batch.append(sample)
                samples_collected += 1

                # Skip samples for other ranks
                for _ in range(self.world_size - 1):
                    try:
                        next(dataset_iter)
                    except StopIteration:
                        break

                # Yield batch when full
                if len(batch) >= self.batch_size:
                    yield_batch = batch[:self.batch_size]
                    batch = batch[self.batch_size:]

                    if self.collate_fn:
                        yield self.collate_fn(yield_batch)
                    else:
                        yield yield_batch

            except StopIteration:
                # End of dataset - yield final batch if needed
                if batch and not self.drop_last:
                    if self.collate_fn:
                        yield self.collate_fn(batch)
                    else:
                        yield batch
                break

    def __len__(self) -> int:
        """Estimate number of batches."""
        try:
            total_samples = len(self.dataset) // self.world_size
            return max(1, total_samples // self.batch_size)
        except:
            return 1000  # Fallback estimate


# Updated dataset creation function
def make_streaming_datasets(tokenized_dir: str, seed: int = 42):
    """Create streaming train and validation datasets from JSON files."""
    try:
        train_ds = ConstrainedJSONDataset(
            tokenized_dir,
            "train",
            shuffle=True,
            seed=seed,
            length_sort_buffer_size=1000  # Helps with batching efficiency
        )
    except FileNotFoundError:
        raise FileNotFoundError(f"No training JSON files found in {tokenized_dir}")

    # For validation, try to create it but fall back gracefully
    try:
        eval_ds = ConstrainedJSONDataset(
            tokenized_dir,
            "val",
            shuffle=False,  # Don't shuffle validation data
            seed=seed,
            length_sort_buffer_size=100  # Smaller buffer for eval
        )
    except FileNotFoundError:
        logging.info("No validation data found, using None for eval_dataset")
        eval_ds = None

    return train_ds, eval_ds


# -------------------------
# Enhanced Trainer with Constraint Loss - FIXED VERSION
# -------------------------
class ConstrainedTrainer(transformers.Trainer):
    """
    Trainer implementing your exact approach with detailed debugging:
    1. Model generates freely
    2. Compute loss on actual predictions (including wrong ones)
    3. If prediction is invalid, substitute with random valid token
    4. Continue with corrected sequence to see full training examples
    """

    def __init__(self, *args, **kwargs):
        # Extract our custom parameters before calling super().__init__
        self.constraint_weight = kwargs.pop('constraint_weight', 0.0)

        # Initialize substitution stats
        self.substitution_stats = {
            'total_substitutions': 0,
            'substitutions_per_step': [],
        }

        # Debug settings
        self.debug_enabled = True
        self.max_debug_samples = 2  # Only debug first 2 samples per batch to avoid spam

        # Now call parent with cleaned kwargs
        super().__init__(*args, **kwargs)

    def compute_loss(self, model, inputs, return_outputs=False, num_items_in_batch=None):
        """
        Implement your exact approach with detailed debugging:
        1. Forward pass with model predictions
        2. Compute loss on actual predictions
        3. Apply token substitution for next step context
        """

        # logging.log("Inputs: " + self.processing_class.decode(inputs["input_ids"][0]))
        # logging.log("Constraints: " + self.processing_class.decode(inputs.get("constraint_sequences", [])[0]))

        labels = inputs.get("labels")
        constraint_sequences = inputs.get("constraint_sequences", [])

        # Remove constraint_sequences from model inputs
        inputs_for_model = {k: v for k, v in inputs.items() if k != "constraint_sequences"}

        # Step 1: Standard forward pass - get model's actual predictions
        outputs = model(**inputs_for_model)

        # Step 2: Compute loss on ORIGINAL predictions (including mistakes)
        loss = self._compute_standard_loss(outputs, labels)

        # Step 3: Apply token substitution ONLY for training context with detailed debugging
        if self.model.training and labels is not None and constraint_sequences:
            self._apply_token_substitution_with_debug(
                inputs_for_model, outputs, labels, constraint_sequences
            )

        return (loss, outputs) if return_outputs else loss

    def _compute_standard_loss(self, outputs, labels):
        """Compute standard loss on model's actual predictions."""
        if labels is not None:
            logits = outputs.logits if hasattr(outputs, 'logits') else outputs[0]
            shift_logits = logits[..., :-1, :].contiguous()
            shift_labels = labels[..., 1:].contiguous()

            loss_fct = torch.nn.CrossEntropyLoss(ignore_index=IGNORE_INDEX)
            loss = loss_fct(
                shift_logits.view(-1, shift_logits.size(-1)),
                shift_labels.view(-1)
            )
        else:
            loss = outputs.loss

        return loss

    def _apply_token_substitution_with_debug(self, inputs, outputs, labels, constraint_sequences):
        """
        Apply token substitution with comprehensive debugging output.
        """
        batch_size = inputs['input_ids'].shape[0]
        step_substitutions = 0

        logging.info(f"\n🔍 === TOKEN SUBSTITUTION DEBUG (Batch size: {batch_size}) ===")

        for batch_idx in range(min(batch_size, self.max_debug_samples)):  # Limit debug output
            if not constraint_sequences[batch_idx]:
                logging.info(f"\n📋 Sample {batch_idx}: No constraint sequences, skipping")
                continue

            logging.info(f"\n📋 === PROCESSING SAMPLE {batch_idx} ===")

            # Build constraint trie for this sample
            constraint_trie = self._build_constraint_trie(constraint_sequences[batch_idx])
            if constraint_trie is None:
                logging.info(f"❌ Could not build constraint trie for sample {batch_idx}")
                continue

            # Show constraint targets
            # Show constraint targets (in the _apply_token_substitution_with_debug method)
            logging.info(f"🎯 Constraint targets:")
            for i, seq in enumerate(constraint_sequences[batch_idx]):
                # if len(constraint_sequences[batch_idx]) < 5:
                if i < 5:
                    logging.info(
                        f"   Target {i + 1}: {seq} → '{[self.processing_class.decode(s, skip_special_tokens=False) for s in seq]}'")
            if len(constraint_sequences[batch_idx]) > 5:
                logging.info(f"   ... and {len(constraint_sequences[batch_idx]) - 5} more")

            # Find generation start
            input_text = self.processing_class.decode(inputs['input_ids'][batch_idx], skip_special_tokens=False)
            if "# EMIT a" in input_text:
                stage = "generation"
            else:
                stage = "selection"
            logging.info(f"   Test prefix:\n{input_text.split("// Test prefix")[1].split("<|fim_suffix|>")[0].strip()}")
            if stage == "selection":
                logging.info(f"   Next candidate tokens:\n{input_text.split("# NEXT TOKEN CANDIDATES")[1].split("# TASK")[0].strip()}")
            logging.info(f"   Stage: {stage}")
            generation_start_pos = self._find_generation_start_position(input_text, inputs['input_ids'][batch_idx])

            if generation_start_pos is None:
                logging.info(f"❌ Could not find generation start position for sample {batch_idx}")
                continue

            logging.info(f"🚀 Generation starts at position {generation_start_pos}")

            # Process sequence token by token with detailed debug
            corrected_prefix = []
            sample_substitutions = 0
            unexpected_token = False
            # Process each generated position
            for pos in range(generation_start_pos, min(generation_start_pos + 10, len(labels[batch_idx]))):  # Limit to first 10 tokens for debug
                if labels[batch_idx, pos] == IGNORE_INDEX:
                    continue
                logging.info(f"\n🔄 --- STEP {pos - generation_start_pos + 1} (Position {pos}) ---")
                # Show current context (corrected prefix)
                if corrected_prefix:
                    current_context_decoded = self.processing_class.decode(corrected_prefix, skip_special_tokens=False)
                    logging.info(f"📝 Current context: {corrected_prefix} → '{current_context_decoded}'")
                else:
                    logging.info(f"📝 Current context: [] → '' (empty)")

                # Get model's prediction at this position
                predicted_token = torch.argmax(outputs.logits[batch_idx, pos - 1]).item()
                ground_truth_token = labels[batch_idx, pos].item()

                # Decode tokens for display
                predicted_decoded = self.processing_class.decode([predicted_token], skip_special_tokens=False)
                ground_truth_decoded = self.processing_class.decode([ground_truth_token], skip_special_tokens=False)

                logging.info(f"🤖 Model predicts: {predicted_token} → '{predicted_decoded}'")
                logging.info(f"✅ Ground truth:   {ground_truth_token} → '{ground_truth_decoded}'")

                # Get valid tokens for current prefix
                valid_tokens = constraint_trie.get_valid_continuations(corrected_prefix)

                if not ground_truth_token in valid_tokens:
                    unexpected_token = True
                    logging.warning(
                        f"⚠️  Ground truth token {ground_truth_token} → '{ground_truth_decoded}' is NOT in valid continuations!")
                if not valid_tokens:
                    logging.warning(f"⚠️  No valid continuations found! Using ground truth to recover.")
                    corrected_prefix.append(ground_truth_token)
                    continue
                # Show valid continuations
                logging.info(f"🎯 Valid next tokens ({len(valid_tokens)}):")
                valid_list = list(valid_tokens)
                for i, token_id in enumerate(valid_list):  # Show first 5
                    decoded = self.processing_class.decode([token_id], skip_special_tokens=False)
                    logging.info(f"   {token_id} → '{decoded}'")

                # Check if prediction is valid
                if predicted_token in valid_tokens:
                    logging.info(f"✅ VALID: Model predicted a valid token {predicted_token} → '{predicted_decoded}'")
                if predicted_token == ground_truth_token:
                    logging.info(f"🎉 Correct prediction! No substitution needed.")
                    corrected_prefix.append(predicted_token)
                else:
                    # Prediction is invalid, substitute with random valid token
                    substitute_token = ground_truth_token  # random.choice(list(valid_tokens))
                    substitute_decoded = self.processing_class.decode([substitute_token], skip_special_tokens=False)
                    corrected_prefix.append(substitute_token)
                    sample_substitutions += 1
                    step_substitutions += 1
                    logging.warning(
                        f"❌ WRONG: Model predicted a different token with respect to the ground truth {predicted_token} → '{predicted_decoded}'")
                    logging.info(f"🔄 SUBSTITUTE: Using {substitute_token} → '{substitute_decoded}' from ground truth")
                    logging.info(f"📊 Substitution #{sample_substitutions} in this sample")
                # Show updated context after this step
                updated_context_decoded = self.processing_class.decode(corrected_prefix, skip_special_tokens=False)
                logging.info(f"📝 Updated context: {corrected_prefix} → '{updated_context_decoded}'")
            logging.info(f"\n📊 Sample {batch_idx} complete: {sample_substitutions} substitutions")
            if unexpected_token:
                logging.info(f"⚠️  Ground truth had tokens outside valid continuations in this sample.")
        # Track substitution statistics
        self.substitution_stats['total_substitutions'] += step_substitutions
        self.substitution_stats['substitutions_per_step'].append(step_substitutions)
        logging.info(f"\n🎉 === BATCH COMPLETE ===")
        logging.info(f"📊 Total substitutions in this batch: {step_substitutions}")
        logging.info(f"📈 Running total substitutions: {self.substitution_stats['total_substitutions']}")

        if step_substitutions > 0:
            logging.info(f"✅ Applied {step_substitutions} token substitutions in this batch")

    def _build_constraint_trie(self, constraint_sequences):
        """Build simplified constraint trie for a single sample - direct from encoded sequences."""
        if not constraint_sequences:
            return None

        # No need to convert - sequences are already encoded!
        return ConstraintTrie(
            constraint_sequences=constraint_sequences,
            processing_class=self.processing_class,
            eos_token_id=self.processing_class.eos_token_id
        )

    def _find_generation_start_position(self, text, input_ids):
        """Find the position where generation starts (after <|fim_middle|>)."""
        fim_middle_marker = "<|fim_middle|>"
        if fim_middle_marker not in text:
            return None

        # Find the token position where <|fim_middle|> ends
        marker_tokens = self.processing_class.encode(fim_middle_marker, add_special_tokens=False)
        if not marker_tokens:
            return None

        # Search for the marker in input_ids
        input_list = input_ids.tolist()
        for i in range(len(input_list) - len(marker_tokens) + 1):
            if input_list[i:i + len(marker_tokens)] == marker_tokens:
                return i + len(marker_tokens)

        return None

    def log_substitution_stats(self):
        """Log statistics about token substitutions."""
        total = self.substitution_stats['total_substitutions']
        recent_steps = self.substitution_stats['substitutions_per_step'][-100:]  # Last 100 steps
        avg_recent = sum(recent_steps) / len(recent_steps) if recent_steps else 0

        logging.info(f"\n📊 === TOKEN SUBSTITUTION STATISTICS ===")
        logging.info(f"   Total substitutions: {total}")
        logging.info(f"   Average per step (last 100): {avg_recent:.2f}")
        logging.info(f"   Steps processed: {len(self.substitution_stats['substitutions_per_step'])}")

    def set_debug_level(self, enabled: bool = True, max_samples: int = 2):
        """Control debug output level."""
        self.debug_enabled = enabled
        self.max_debug_samples = max_samples
        logging.info(f"🔧 Debug level set: enabled={enabled}, max_samples={max_samples}")


class StreamingConstrainedTrainer(ConstrainedTrainer):
    """
    Enhanced trainer that properly handles streaming datasets.
    """

    def get_train_dataloader(self):
        """Create train dataloader for streaming dataset."""
        if hasattr(self.train_dataset, '__iter__'):  # Streaming dataset
            # Use custom streaming dataloader
            world_size = getattr(self.args, 'world_size', 1)
            rank = getattr(self.args, 'process_index', 0)

            dataloader = StreamingDataLoader(
                dataset=self.train_dataset,
                batch_size=self.args.per_device_train_batch_size,
                world_size=world_size,
                rank=rank,
                collate_fn=self.data_collator,
                drop_last=True,
            )

            return dataloader
        else:
            # Fallback to parent implementation for regular datasets
            return super().get_train_dataloader()

    def get_eval_dataloader(self, eval_dataset=None):
        """Create eval dataloader for streaming dataset."""
        if eval_dataset is None:
            eval_dataset = self.eval_dataset

        if eval_dataset is not None and hasattr(eval_dataset, '__iter__'):
            # Use custom streaming dataloader for eval
            world_size = getattr(self.args, 'world_size', 1)
            rank = getattr(self.args, 'process_index', 0)

            dataloader = StreamingDataLoader(
                dataset=eval_dataset,
                batch_size=self.args.per_device_eval_batch_size,
                world_size=world_size,
                rank=rank,
                collate_fn=self.data_collator,
                drop_last=False,  # Don't drop last for evaluation
            )

            return dataloader
        else:
            # Fallback to parent implementation
            return super().get_eval_dataloader(eval_dataset)


# -------------------------
# Callbacks (unchanged from original)
# -------------------------

class DetailedSubstitutionStatsCallback(transformers.TrainerCallback):
    """Enhanced callback for detailed substitution statistics."""

    def __init__(self, log_every_n_steps: int = 50):
        self.log_every_n_steps = log_every_n_steps

    def on_step_end(self, args, state, control, **kwargs):
        trainer = kwargs.get('trainer')
        if trainer and hasattr(trainer, 'log_substitution_stats'):
            if state.global_step % self.log_every_n_steps == 0:
                trainer.log_substitution_stats()

    def on_epoch_end(self, args, state, control, **kwargs):
        trainer = kwargs.get('trainer')
        if trainer and hasattr(trainer, 'log_substitution_stats'):
            logging.info(f"\n🎯 === END OF EPOCH {state.epoch} ===")
            trainer.log_substitution_stats()


class SaveModelCallback(transformers.TrainerCallback):
    def on_save(self, args, state, control, **kwargs):
        # Log from rank 0 if distributed; otherwise just log.
        rank0 = True
        if torch.distributed.is_available() and torch.distributed.is_initialized():
            try:
                rank0 = (torch.distributed.get_rank() == 0)
            except Exception:
                rank0 = True
        if rank0:
            logging.info(f"[save] {args.output_dir}/checkpoint-{state.global_step}/")
        # No barrier here — Trainer's save runs on rank 0 only.
        return control


class LoggingCallback(transformers.TrainerCallback):
    def __init__(self, log_memory: bool = True):
        self.last_time = None
        self.last_step = 0
        self.total_tokens_seen = 0
        self.log_memory = log_memory

    def on_log(self, args, state, control, logs=None, **kwargs):
        if logs is None:
            return

        now = time.time()
        world = getattr(args, 'world_size', 1)

        if self.last_time is None:
            self.last_time = now

        include_toks_seen = getattr(args, 'include_num_input_tokens_seen', False)
        if include_toks_seen and getattr(state, 'num_input_tokens_seen', None) is not None:
            delta_tokens = state.num_input_tokens_seen - self.total_tokens_seen * world
            self.total_tokens_seen = state.num_input_tokens_seen
        else:
            steps_elapsed = state.global_step - self.last_step
            est_tokens_per_step = args.per_device_train_batch_size * args.model_max_length * world
            delta_tokens = est_tokens_per_step * max(0, steps_elapsed)
            self.last_step = state.global_step

        dt = max(1e-6, now - self.last_time)
        tps = int(delta_tokens / dt)

        msg = (f"step {state.global_step}, epoch {state.epoch}, "
               f"loss {logs.get('loss')}, eval_loss {logs.get('eval_loss')}, "
               f"lr {logs.get('learning_rate')}, tokens_per_sec {tps}")

        if self.log_memory and torch.cuda.is_available():
            try:
                mem = torch.cuda.max_memory_allocated() / 1000000
                msg += f", gpu_mem_MiB {int(mem)}"
                torch.cuda.reset_peak_memory_stats()
            except Exception:
                pass

        # FIXED: Use proper distributed check instead of self.trainer
        try:
            # Check if we're in distributed mode and if this is rank 0
            if torch.distributed.is_available() and torch.distributed.is_initialized():
                if torch.distributed.get_rank() == 0:
                    logging.info("------------------")
                    logging.info(msg)
                    logging.info("------------------")
            else:
                # Single process mode
                logging.info("------------------")
                logging.info(msg)
                logging.info("------------------")
        except Exception:
            # Fallback for any distributed issues
            logging.info("------------------")
            logging.info(msg)
            logging.info("------------------")

        self.last_time = now


class DelayedEarlyStopping(transformers.EarlyStoppingCallback):
    """
    Wraps Hugging Face EarlyStopping but ignores evaluate calls until epoch >= min_early_stopping_epochs.
    """

    def __init__(
            self,
            min_early_stopping_epochs: int = 1,
            early_stopping_patience: int = 2,
            early_stopping_threshold: float = 0.0
    ):
        super().__init__(
            early_stopping_patience=early_stopping_patience,
            early_stopping_threshold=early_stopping_threshold
        )
        self._min_epochs = min_early_stopping_epochs

    def on_evaluate(self, args, state, control, **kwargs):
        # Defer early stopping decisions until after min epochs are finished
        if state.epoch is not None and state.epoch < self._min_epochs:
            return control

        return super().on_evaluate(args, state, control, **kwargs)


class SampleGenerationCallback(transformers.TrainerCallback):
    def __init__(self, tokenizer, prompts, every_n_steps=5, max_new_tokens=64, **gen_kwargs):
        self.tok = tokenizer
        self.prompts = list(prompts)
        self.every = int(every_n_steps)
        self.max_new_tokens = int(max_new_tokens)
        self.gen_kwargs = {"do_sample": True, "temperature": 0.7}
        self.gen_kwargs.update(gen_kwargs)

    def _format(self, p):
        # If it's a chat model, try to use the chat template; otherwise raw text.
        try:
            return self.tok.apply_chat_template(
                [{"role": "user", "content": p}],
                tokenize=False,
                add_generation_prompt=True,
            )
        except Exception:
            return p

    def _extract_constraint_sequences(self, prompt: str) -> List[List[int]]:
        """
        Extract constraint sequences - now just returns the encoded target strings.
        The StringBasedConstraintTrie will handle tokenization variations.
        """
        import re

        if "# TASK: Choose exactly one" not in prompt:
            return []

        candidate_tokens = []

        # Extract candidates
        candidates_match = re.search(
            r'# NEXT TOKEN CANDIDATES.*?(?=# TASK:|$)',
            prompt,
            re.DOTALL
        )

        if not candidates_match:
            return []

        candidates_section = candidates_match.group(0)

        for pattern in [r'# PROJECT TOKENS:\s*\[(.*?)\]',
                        r'# JAVA TOKENS:\s*\[(.*?)\]',
                        r'# GENERIC TOKENS:\s*\[(.*?)\]']:
            match = re.search(pattern, candidates_section, re.DOTALL)
            if match:
                tokens_str = match.group(1)
                tokens = re.findall(r'"([^"]+)"', tokens_str)
                candidate_tokens.extend(tokens)

        logging.info(f"\n[DEBUG] Extracted {len(candidate_tokens)} candidates")

        # Build constraint sequences (one per candidate)
        constraint_sequences = []
        for candidate_token in candidate_tokens:
            complete_output = f'{{"choice":"{candidate_token}"}}'
            token_ids = self.tok.encode(complete_output, add_special_tokens=False)
            if token_ids:
                constraint_sequences.append(token_ids)

        return constraint_sequences

    def _create_constrained_logits_processor(self, prompt: str) -> Optional[transformers.LogitsProcessorList]:
        """Create a LogitsProcessorList with constraints for the given prompt."""

        # Extract constraints from the prompt
        constraint_sequences = self._extract_constraint_sequences(prompt)
        if not constraint_sequences:
            return None  # No constraints for generation stage
        # Detect stage
        if "# EMIT a" in prompt:
            stage = "generation"
        else:
            stage = "selection"
        # Create the constraint processor
        processor = AdvancedConstrainedLogitsProcessor(
            tokenizer=self.tok,
            constraint_sequences=constraint_sequences,
            stage=stage,
            enable_training_constraints=True
        )
        # Return as LogitsProcessorList
        return transformers.LogitsProcessorList([processor])

    def on_step_end(self, args, state, control, **kwargs):
        # Only occasionally
        if state.global_step == 0 or state.global_step % self.every != 0:
            return
        # Use distributed check instead of trainer reference
        import torch
        if torch.distributed.is_initialized() and torch.distributed.get_rank() != 0:
            return
        model = kwargs["model"]
        was_train = model.training
        prev_use_cache = getattr(model.config, "use_cache", False)
        try:
            model.eval()
            # Generation prefers use_cache=True even if training disabled it.
            model.config.use_cache = True
            # Debug PEFT status without trying to enable adapters
            logging.info(f"Model has PEFT: {hasattr(model, 'peft_config')}\n")
            if hasattr(model, 'peft_config'):
                logging.info(f"PEFT config: {model.peft_config}\n")
                if hasattr(model, 'active_adapters'):
                    logging.info(f"Active adapters: {model.active_adapters}\n")
            # Check if model is a PeftModel
            logging.info(f"Model type: {type(model)}\n")
            logging.info(f"=== SAMPLE GENERATIONS @ step {state.global_step} ===\n")
            for p in self.prompts:
                text_in = p
                inputs = self.tok(text_in, return_tensors="pt").to(model.device)
                # Create constrained logits processor for this prompt
                logits_processor = self._create_constrained_logits_processor(p)
                # Debug: Show if constraints are being applied
                if logits_processor is not None:
                    logging.info(f"✅ Applying constraints during generation for this prompt\n")
                else:
                    logging.info(f"⚠️ No constraints for this prompt (generation stage)\n")
                with torch.no_grad():
                    # CRITICAL FIX: Pass logits_processor to generate
                    gen_kwargs_with_constraints = self.gen_kwargs.copy()
                    if logits_processor is not None:
                        gen_kwargs_with_constraints['logits_processor'] = logits_processor
                    out = model.generate(
                        **inputs,
                        min_new_tokens=3,
                        max_new_tokens=self.max_new_tokens,
                        pad_token_id=self.tok.pad_token_id,
                        eos_token_id=self.tok.eos_token_id,
                        **gen_kwargs_with_constraints,
                    )
                # Slice off the prompt portion
                gen_tokens = out[0][inputs["input_ids"].shape[1]:]
                text_out = self.tok.decode(gen_tokens, skip_special_tokens=True)
                logging.info(f"Generated token IDs: {out[0].tolist()}\n")
                logging.info(f"Input length: {inputs['input_ids'].shape[1]}\n")
                logging.info(f"Output length: {out[0].shape[0]}\n")
                logging.info(f"New tokens: {out[0][inputs['input_ids'].shape[1]:].tolist()}\n")
                logging.info(f"Prompt: {p}\nOutput: {text_out}\n---")

        finally:
            model.config.use_cache = prev_use_cache
            if was_train:
                model.train()


# -------------------------
# Utility Functions
# -------------------------

def _resolve_snapshot(repo_or_path: str, revision: Optional[str]) -> tuple[str, str]:
    """
    Return (load_path, used_revision). If local directory, returns ('/abs/path', 'local').
    Otherwise, downloads/uses cached snapshot and returns its directory and commit sha.
    """
    if os.path.isdir(repo_or_path):
        return os.path.abspath(repo_or_path), "local"

    snap_dir = snapshot_download(repo_id=repo_or_path, revision=revision)
    used_rev = Path(snap_dir).name
    return snap_dir, used_rev


def make_datasets(tokenized_dir: str):
    """Create train and validation datasets from JSON files."""
    try:
        train_ds = ConstrainedJSONDataset(tokenized_dir, "train")
    except FileNotFoundError:
        raise FileNotFoundError(f"No training JSON files found in {tokenized_dir}")
    # For validation, try to create it but fall back gracefully if no val data
    try:
        eval_ds = ConstrainedJSONDataset(tokenized_dir, "val")
    except FileNotFoundError:
        logging.info("No validation data found, using None for eval_dataset")
        eval_ds = None
    return train_ds, eval_ds


def debug_constraint_alignment(dataset, tokenizer, num_samples=20):
    """Check if constraints match task types."""
    print("\n" + "=" * 80)
    print("CONSTRAINT ALIGNMENT DEBUG")
    print("=" * 80)

    mismatches = []

    for i in range(min(num_samples, len(dataset))):
        sample = dataset[i]
        input_ids = sample['input_ids']
        constraints = sample.get('constraint_sequences', [])

        input_text = tokenizer.decode(input_ids, skip_special_tokens=False)

        is_generation = "EMIT a" in input_text
        is_selection = "TASK: Choose exactly one" in input_text

        task_type = "generation" if is_generation else "selection" if is_selection else "unknown"

        print(f"\nSample {i}: {task_type}, {len(constraints)} constraints", end="")

        if is_generation and len(constraints) > 0:
            print(f" ❌ MISMATCH")
            mismatches.append(i)
        elif is_selection and len(constraints) == 0:
            print(f" ❌ MISMATCH")
            mismatches.append(i)
        else:
            print(f" ✅")

    print(f"\n{'=' * 80}")
    print(f"SUMMARY: {len(mismatches)} mismatches out of {num_samples}")
    return mismatches


# -------------------------
# Main Training Function
# -------------------------

def train():
    # Parse arguments
    parser = transformers.HfArgumentParser((ModelArguments, DataArguments, TrainingArguments))
    model_args, data_args, training_args = parser.parse_args_into_dataclasses()

    # Resolve exact base snapshot (even if --model_revision is None)
    base_load_path, used_revision = _resolve_snapshot(model_args.model_name_or_path, model_args.model_revision)

    # Create the absolute path to the output of the training if it does not exist
    os.makedirs(training_args.output_dir, exist_ok=True)

    # Load tokenizer (matching the tokenizer used to encode and tokenize the dataset)
    assert data_args.tokenized_dir, "--tokenized_dir is required and must point to the JSON files."

    tokenizer = load_tokenizer_consistent(
        model_args.model_name_or_path,
        training_args.model_max_length,
        data_args.tokenized_dir,
        revision=model_args.model_revision,
        special_tokens=[]  # FIM tokens are added automatically
    )

    # Model (QLoRA 4-bit)
    bnb_config = BitsAndBytesConfig(
        load_in_4bit=True,
        bnb_4bit_use_double_quant=True,
        bnb_4bit_quant_type="nf4",
        bnb_4bit_compute_dtype=torch.bfloat16
    )

    attn_impl = "flash_attention_2" if model_args.use_flash_attention else "sdpa"
    model = transformers.AutoModelForCausalLM.from_pretrained(
        base_load_path,
        quantization_config=bnb_config,
        attn_implementation=attn_impl,
        trust_remote_code=True,
    )

    # Ensure model embeddings match tokenizer (resize if tokenizer has new tokens)
    if len(tokenizer) != model.config.vocab_size:
        model.resize_token_embeddings(len(tokenizer))

    # Prepare model for kbit training if using PEFT
    if training_args.use_peft:
        model = prepare_model_for_kbit_training(model, use_gradient_checkpointing=training_args.gradient_checkpointing)

        # LoRA config
        peft_config = LoraConfig(
            r=16,
            lora_alpha=32,
            lora_dropout=0.1,
            bias="none",
            task_type="CAUSAL_LM",
            target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
        )

        model = get_peft_model(model, peft_config)
        model.print_trainable_parameters()

    # Load JSON datasets
    train_ds, eval_ds = make_streaming_datasets(data_args.tokenized_dir, seed=training_args.seed)

    # Tokenizer consistency checks (updated for JSON)
    assert_meta_compatible(data_args.tokenized_dir, tokenizer, [], "train")
    if eval_ds is not None:
        assert_meta_compatible(data_args.tokenized_dir, tokenizer, [], "val")

    # Enhanced data collator with constraint support
    data_collator = ConstraintAwareDataCollator(
        processing_class=tokenizer,
        pad_to_multiple_of=8,
        static_pad_to=None,
    )

    # Callbacks (unchanged)
    sample_prompts = [sample_query_1, sample_query_2, sample_query_3]
    callbacks = [
        SaveModelCallback(),
        LoggingCallback(log_memory=True),
        DelayedEarlyStopping(
            min_early_stopping_epochs=training_args.min_early_stopping_epochs,
            early_stopping_patience=training_args.early_stopping_patience,
        ),
        # SampleGenerationCallback(
        #     tokenizer=tokenizer,
        #     prompts=sample_prompts,
        #     every_n_steps=5,
        #     max_new_tokens=128,
        #     temperature=0.7,
        #     do_sample=True,
        # )
    ]

    # Enhanced trainer with constraint support
    trainer = StreamingConstrainedTrainer(
        model=model,
        args=training_args,
        train_dataset=train_ds,
        eval_dataset=eval_ds,
        processing_class=tokenizer,
        data_collator=data_collator,
        callbacks=callbacks,
    )

    # Add detailed callback
    trainer.add_callback(DetailedSubstitutionStatsCallback(log_every_n_steps=1))

    # Enable detailed debugging (set to False to reduce output)
    trainer.set_debug_level(enabled=True, max_samples=2)

    # mismatches = debug_constraint_alignment(train_ds, tokenizer, 50)
    # if mismatches:
    #     print("⚠️ Constraint alignment issues detected!")

    # Train
    if list(pathlib.Path(training_args.output_dir).glob("checkpoint-*")):
        trainer.train(resume_from_checkpoint=True)
    else:
        trainer.train()

    # Save final model
    trainer.save_state()
    if training_args.use_peft:
        # For PEFT, save adapter only
        trainer.model.save_pretrained(training_args.output_dir)
    else:
        # For full fine-tuning, save full model
        trainer.save_model()

if __name__ == "__main__":
    import pathlib

    train()