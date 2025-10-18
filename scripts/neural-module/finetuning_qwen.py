#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Fine-tuning script with Trie/WFSA-based Constrained Decoding for Oracle Token-by-Token Generation.
Supports both training-time and inference-time constraint enforcement using efficient trie structures.
"""

import time, json, logging, glob, bisect, re
from dataclasses import dataclass, field
from typing import Dict, Optional, List, Tuple, Set
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
Output ONLY the literal including quotes, with valid Java escapes. Nothing else.

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
    constraint_weight: float = field(default=0.1, metadata={"help": "Weight for constraint loss"})
    enable_training_constraints: bool = field(default=True, metadata={"help": "Enable constraints during training"})


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
    """Trie structure for efficient constraint lookup with training support"""

    def __init__(self, constraint_sequences: List[List[int]], processing_class: Optional[AutoTokenizer] = None, eos_token_id: Optional[int] = None):
        self.root = TrieNode()
        self.eos_token_id = eos_token_id
        self.processing_class = processing_class
        self._build_trie(constraint_sequences)

    def _build_trie(self, sequences: List[List[int]]):
        """Build trie from pre-computed constraint sequences"""
        for seq in sequences:
            # logging.info(f"Seq encoded: {seq}")
            # logging.info(f"Seq decoded: {[self.processing_class.decode(s, skip_special_tokens=False) for s in seq if self.processing_class]}")
            if not seq:  # Skip empty sequences
                continue
            current = self.root
            for depth, token_id in enumerate(seq):
                if token_id not in current.children:
                    current.children[token_id] = TrieNode()
                    current.children[token_id].depth = depth + 1
                current = current.children[token_id]

            # Mark as terminal
            current.is_terminal = True

            # Add EOS token as valid continuation from terminal states
            if self.eos_token_id is not None:
                if self.eos_token_id not in current.children:
                    current.children[self.eos_token_id] = TrieNode()
                    current.children[self.eos_token_id].depth = len(seq) + 1
                    current.children[self.eos_token_id].is_terminal = True

    def get_valid_continuations(self, prefix: List[int]) -> Set[int]:
        """Get valid token IDs that can continue the given prefix"""
        current = self.root
        for token_id in prefix:
            if token_id not in current.children:
                return set()  # Invalid prefix
            current = current.children[token_id]

        valid_tokens = set(current.children.keys())

        # If we're at a terminal node and EOS is configured, ensure it's available
        if current.is_terminal and self.eos_token_id is not None:
            valid_tokens.add(self.eos_token_id)
        # logging.info(f"Prefix encoded: {prefix}")
        # logging.info(f"Prefix decoded: {[self.processing_class.decode(p, skip_special_tokens=False) for p in prefix if self.processing_class ]}")
        # logging.info(f"Valid tokens encoded: {valid_tokens}")
        # logging.info(f"Valid tokens decoded: {[self.processing_class.decode(v, skip_special_tokens=False) for v in list(valid_tokens) if self.processing_class]}")
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
    """Trie-based constrained decoding with training and inference support"""

    def __init__(self, tokenizer, constraint_sequences: List[List[int]], stage: str = "selection",
                 enable_training_constraints: bool = True):
        self.processing_class = tokenizer
        self.stage = stage
        self.enable_training_constraints = enable_training_constraints

        # Build constraint trie
        self.constraint_trie = ConstraintTrie(constraint_sequences) if constraint_sequences else None

        # JSON structure tokens (always allowed)
        self.json_tokens = self._get_json_token_ids()

        # Special tokens that should always be allowed
        self.special_tokens = self._get_special_token_ids()

        # Stage detection patterns
        self.stage_patterns = {
            "selection": re.compile(r'#\s*TASK:\s*Choose exactly one.*?#\s*OUTPUT:.*?$', re.MULTILINE | re.DOTALL),
            "generation": re.compile(r'#\s*EMIT a.*?#\s*OUTPUT:.*?$', re.MULTILINE | re.DOTALL)
        }

    def _get_json_token_ids(self) -> Set[int]:
        """Get token IDs for JSON structure"""
        json_elements = ['{', '}', '"', ':', ' ', 'choice', '\\']
        token_ids = set()

        for elem in json_elements:
            # Handle different tokenization patterns
            tokens = self.processing_class.encode(elem, add_special_tokens=False)
            token_ids.update(tokens)

            # Also handle as part of larger JSON structures
            json_variants = [f'"{elem}"', f' {elem}', f'{elem} ']
            for variant in json_variants:
                variant_tokens = self.processing_class.encode(variant, add_special_tokens=False)
                token_ids.update(variant_tokens)

        return token_ids

    def _get_special_token_ids(self) -> Set[int]:
        """Get IDs for special tokens"""
        special_ids = set()
        if self.processing_class.eos_token_id is not None:
            special_ids.add(self.processing_class.eos_token_id)
        if self.processing_class.pad_token_id is not None:
            special_ids.add(self.processing_class.pad_token_id)
        if hasattr(self.processing_class, 'fim_middle_id'):
            special_ids.add(self.processing_class.fim_middle_id)
        return special_ids

    def _detect_generation_stage(self, text: str) -> str:
        """Detect which stage we're in based on prompt content"""
        if self.stage_patterns["generation"].search(text):
            return "generation"
        elif self.stage_patterns["selection"].search(text):
            return "selection"
        else:
            # Fallback: check for choice pattern
            if re.search(r'\{"choice":\s*"([^"]*)"}$', text):
                logging.info("Detected selection stage based on JSON pattern.")
                return "selection"
            return "generation"

    def _extract_current_json_context(self, text: str) -> List[int]:
        """Extract current JSON generation context for trie lookup"""
        # Find the last JSON structure being built
        json_match = re.search(r'\{"choice":\s*"([^"]*)"}$', text)
        # logging.info(f"Extracting current JSON context from {text}")
        if json_match:
            partial_value = json_match.group(1)
            # Convert to token IDs for trie traversal
            return self.processing_class.encode(partial_value, add_special_tokens=False)

        # Look for opening brace
        if '{"choice":' in text:
            return []  # Starting JSON generation

        return []

    def __call__(self, input_ids: torch.Tensor, scores: torch.Tensor) -> torch.Tensor:
        """Apply constraints to logits"""
        if not self.enable_training_constraints:
            return scores

        # Get current text context
        current_text = self.processing_class.decode(input_ids[0], skip_special_tokens=False)
        current_stage = self._detect_generation_stage(current_text)

        # Stage 2: Free generation for literal values (less constrained)
        if current_stage == "generation":
            return self._apply_generation_constraints(input_ids, scores, current_text)

        # Stage 1: Strict constraint enforcement
        return self._apply_selection_constraints(input_ids, scores, current_text)

    def _apply_selection_constraints(self, input_ids: torch.Tensor, scores: torch.Tensor, text: str) -> torch.Tensor:
        """Apply strict constraints for token selection stage"""
        if self.constraint_trie is None:
            return scores  # No constraints available

        # Extract current JSON context
        json_context = self._extract_current_json_context(text)

        # Get valid continuations from trie
        valid_tokens = self.constraint_trie.get_valid_continuations(json_context)

        # Always allow JSON structure and special tokens
        allowed_tokens = valid_tokens.union(self.json_tokens).union(self.special_tokens)

        # logging.info(f"Allowed tokens encoded: {allowed_tokens}")
        # logging.info(f"Allowed tokens decoded: {[self.processing_class.decode(t, skip_special_tokens=False) for t in list(allowed_tokens) if self.processing_class]}")

        # Create constraint mask
        constraint_mask = torch.full_like(scores, float('-inf'))
        for token_id in allowed_tokens:
            if token_id < scores.shape[-1]:
                constraint_mask[:, token_id] = 0.0

        return scores + constraint_mask

    def _apply_generation_constraints(self, input_ids: torch.Tensor, scores: torch.Tensor, text: str) -> torch.Tensor:
        """Apply relaxed constraints for literal generation stage"""
        # Let the model generate freely during literal phase
        # The trie-based constraints already ensure valid JSON structure
        return scores


# -------------------------
# Enhanced Dataset with Constraint Support - FIXED VERSION
# -------------------------

class ConstrainedMemmapShardDataset(Dataset):
    """
    FIXED: Extended MemmapShardDataset that loads pre-computed constraint sequences from binary shards.
    Properly handles separate constraint files alongside main dataset files.
    """

    def __init__(self, root: str, split: str):
        self.root = root
        self.split = split
        self.shards: List[Dict] = []
        self.cum_counts: List[int] = []

        # CRITICAL FIX: Only look for main dataset .idx files, exclude constraint .idx files
        all_idx_paths = sorted(glob.glob(os.path.join(root, f"{split}.*.idx")))

        # Filter out constraint index files to get only main dataset files
        main_idx_paths = []
        for path in all_idx_paths:
            if not path.endswith('.constraints.idx'):
                main_idx_paths.append(path)

        if not main_idx_paths:
            print(f"All .idx files found: {[os.path.basename(p) for p in all_idx_paths]}")
            raise FileNotFoundError(f"No {split} main dataset shards (.idx) found in {root}")

        print(f"Using main dataset files: {[os.path.basename(p) for p in main_idx_paths]}")

        # Load each shard's offsets and compute lengths
        lengths_chunks: List[np.ndarray] = []
        total = 0
        for idx_path in main_idx_paths:
            # Get the correct base path (without .idx extension)
            base = os.path.splitext(idx_path)[0]  # e.g., "train.00000" from "train.00000.idx"

            # Standard shard files
            ids_path = base + ".input_ids.bin"
            lbl_path = base + ".labels.bin"

            # Constraint files (separate naming but same base)
            constraints_path = base + ".constraints.bin"
            constraints_idx_path = base + ".constraints.idx"

            # Verify main files exist
            if not os.path.exists(ids_path):
                raise FileNotFoundError(f"Missing input_ids file: {ids_path}")
            if not os.path.exists(lbl_path):
                raise FileNotFoundError(f"Missing labels file: {lbl_path}")

            # Load offsets (N+1 entries)
            offsets = np.fromfile(idx_path, dtype=np.uint64)
            if offsets.size < 2:
                continue
            n_samples = offsets.size - 1

            self.shards.append({
                "base": base,
                "ids_path": ids_path,
                "lbl_path": lbl_path,
                "constraints_path": constraints_path,
                "constraints_idx_path": constraints_idx_path,
                "idx_path": idx_path,
                "offsets": offsets,
                "n": n_samples,
            })

            # Length computation (tokens per sample)
            lengths = np.diff(offsets).astype(np.int32)
            lengths_chunks.append(lengths)
            total += n_samples
            self.cum_counts.append(total)

        if total == 0:
            raise RuntimeError(f"{split}: zero samples found in {root}")

        print(f"Successfully loaded {len(self.shards)} shards with {total} total samples")

        # Concatenate all per-shard lengths
        self.lengths: np.ndarray = np.concatenate(lengths_chunks, axis=0)

        # Per-worker cache for memmaps and constraints
        self._ids_mm = [None] * len(self.shards)
        self._lbl_mm = [None] * len(self.shards)
        self._constraints_idx_cache = {}  # Cache for constraint indices

    def __len__(self) -> int:
        return self.cum_counts[-1]

    def _open_mm(self, shard_idx: int):
        if self._ids_mm[shard_idx] is None:
            ids_path = self.shards[shard_idx]["ids_path"]
            lbl_path = self.shards[shard_idx]["lbl_path"]
            self._ids_mm[shard_idx] = np.memmap(ids_path, mode="r", dtype=np.int32)
            self._lbl_mm[shard_idx] = np.memmap(lbl_path, mode="r", dtype=np.int32)

    def _load_constraints_index(self, shard_idx: int) -> Optional[np.ndarray]:
        """Load constraint index for a shard"""
        if shard_idx in self._constraints_idx_cache:
            return self._constraints_idx_cache[shard_idx]

        constraints_idx_path = self.shards[shard_idx]["constraints_idx_path"]
        if not os.path.exists(constraints_idx_path):
            self._constraints_idx_cache[shard_idx] = None
            return None

        try:
            constraints_dtype = np.dtype([('offset', np.uint64), ('length', np.uint32)])
            idx_array = np.fromfile(constraints_idx_path, dtype=constraints_dtype)
            self._constraints_idx_cache[shard_idx] = idx_array
            return idx_array
        except:
            self._constraints_idx_cache[shard_idx] = None
            return None

    def _read_constraints_for_sample(self, shard_idx: int, sample_idx: int) -> List[List[int]]:
        """Read constraint sequences for a specific sample using the binary format"""
        constraints_path = self.shards[shard_idx]["constraints_path"]
        if not os.path.exists(constraints_path):
            return []

        # Load constraint index
        idx_array = self._load_constraints_index(shard_idx)
        if idx_array is None or sample_idx >= len(idx_array):
            return []

        # Get offset and length for this sample
        offset, length = idx_array[sample_idx]['offset'], idx_array[sample_idx]['length']
        if length == 0:
            return []

        try:
            with open(constraints_path, 'rb') as f:
                f.seek(offset)
                data = f.read(length)

            # Parse the binary data according to ShardWriter format
            constraint_seqs = []
            pos = 0

            # Read number of sequences
            if pos + 4 > len(data):
                return []
            num_seqs = np.frombuffer(data[pos:pos + 4], dtype=np.uint32)[0]
            pos += 4

            # Read each sequence
            for _ in range(num_seqs):
                if pos + 4 > len(data):
                    break

                # Read sequence length
                seq_len = np.frombuffer(data[pos:pos + 4], dtype=np.uint32)[0]
                pos += 4

                if pos + seq_len * 4 > len(data):
                    break

                # Read sequence token IDs
                seq = np.frombuffer(data[pos:pos + seq_len * 4], dtype=np.int32).tolist()
                pos += seq_len * 4

                constraint_seqs.append(seq)

            return constraint_seqs
        except Exception as e:
            logging.warning(f"Error reading constraints for shard {shard_idx}, sample {sample_idx}: {e}")
            return []

    def _locate(self, i: int) -> Tuple[int, int]:
        # map global index -> (shard_idx, local_idx)
        shard_idx = bisect.bisect_right(self.cum_counts, i)
        prev_total = 0 if shard_idx == 0 else self.cum_counts[shard_idx - 1]
        local_idx = i - prev_total
        return shard_idx, local_idx

    def __getitem__(self, i: int) -> Dict[str, torch.Tensor]:
        shard_idx, j = self._locate(i)
        self._open_mm(shard_idx)

        off = self.shards[shard_idx]["offsets"]
        a = int(off[j])
        b = int(off[j + 1])

        # Copy + cast to torch.long (int64)
        ids = torch.tensor(self._ids_mm[shard_idx][a:b], dtype=torch.long)
        lbl = torch.tensor(self._lbl_mm[shard_idx][a:b], dtype=torch.long)

        # Load pre-computed constraint sequences from binary format
        constraint_sequences = self._read_constraints_for_sample(shard_idx, j)

        return {
            "input_ids": ids,
            "labels": lbl,
            "constraint_sequences": constraint_sequences  # Already as token ID sequences
        }


# -------------------------
# Rest of the classes remain the same (Block Distributed Sampler, etc.)
# -------------------------

class BlockDistributedSampler(Sampler):
    """
    Deterministic block sampler for DDP:
    • Same shuffle on every rank per epoch (uses seed+epoch)
    • Drops partial blocks so all blocks have identical size
    • Trims to a multiple of world_size, then partitions by striding
    • Drops tail to a multiple of batch_size so all ranks have identical steps
    """

    def __init__(
            self,
            dataset,
            batch_size: int,
            world_size: int = 1,
            rank: int = 0,
            block_size: int = 1024,
            sort_within_block: bool = True,
            seed: int = 42,
    ):
        self.ds = dataset
        self.bs = int(batch_size)
        self.ws = int(world_size)
        self.rank = int(rank)
        self.block_size = int(block_size)
        self.sort_within_block = bool(sort_within_block)
        self.seed = int(seed)
        self.epoch = 0

        # Build full-size blocks of global indices across shards
        self.blocks = []
        start = 0
        for shard in dataset.shards:
            n = int(shard["n"])
            gidx = np.arange(start, start + n, dtype=np.int64)
            full_n = (n // self.block_size) * self.block_size  # drop tail
            if full_n > 0:
                for i in range(0, full_n, self.block_size):
                    blk = gidx[i: i + self.block_size]
                    if self.sort_within_block:
                        lens = dataset.lengths[blk]
                        blk = blk[np.argsort(-lens, kind="mergesort")]  # longest→shortest
                    self.blocks.append(blk)
            start += n

        if len(self.blocks) == 0:
            raise RuntimeError(
                "BlockDistributedSampler: no full blocks were constructed. "
                "Decrease block_size or check dataset."
            )

        # Pre-compute per-rank length (stable across epochs)
        usable_blocks = (len(self.blocks) // self.ws) * self.ws
        per_rank_blocks = usable_blocks // self.ws
        per_rank_samples = per_rank_blocks * self.block_size

        # After drop_last at batch granularity
        self._len = (per_rank_samples // self.bs) * self.bs

    def set_epoch(self, epoch: int):
        # HF/Accelerate calls this each epoch; used to vary the shuffle deterministically.
        self.epoch = int(epoch)

    def __iter__(self):
        # Same permutation on every rank for this epoch
        rng = random.Random(self.seed + self.epoch)
        blocks = list(self.blocks)
        rng.shuffle(blocks)

        # Trim to multiple of world_size; then deterministic partition by striding
        usable = (len(blocks) // self.ws) * self.ws
        blocks = blocks[:usable]
        rank_blocks = blocks[self.rank:: self.ws]

        # Flatten indices for this rank
        idx = np.concatenate(rank_blocks, axis=0)

        # Ensure equal number of batches on every rank
        if self.bs > 1:
            idx = idx[: (len(idx) // self.bs) * self.bs]

        return iter(idx.tolist())

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
    Basic sanity checks between meta and the runtime tokenizer.
    """
    meta_path = os.path.join(tokenized_dir, f"{split}.meta.json")
    if not os.path.isfile(meta_path):
        logging.warning(f"{meta_path} not found; skipping tokenizer/meta consistency checks for split={split}.")
        return

    with open(meta_path, "r") as f:
        meta = json.load(f)

    # pad/eos should match; special tokens should exist
    expected = meta.get("vocab_size") or meta.get("vocab_size_after")
    if expected is not None:
        assert len(tok) == int(expected), f"vocab size mismatch: {len(tok)} vs {expected}"

    sti = meta.get("special_token_ids") or {}
    for s in special_tokens:
        if s in sti:
            assert tok(s).input_ids[0] == sti[s]

    if "pad_token_id" in meta and meta["pad_token_id"] is not None:
        assert tok.pad_token_id == meta["pad_token_id"], \
            f"pad_token_id mismatch: tokenizer={tok.pad_token_id}, meta={meta['pad_token_id']}"

    if "eos_token_id" in meta and meta["eos_token_id"] is not None:
        assert tok.eos_token_id == meta["eos_token_id"], \
            f"eos_token_id mismatch: tokenizer={tok.eos_token_id}, meta={meta['eos_token_id']}"

    for t in ["<|im_start|>", "<|im_end|>"]:
        tid = tok(t).input_ids[0]
        if tid < 0:
            raise RuntimeError(f"Token {t} missing in tokenizer.")


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
            "constraint_sequences": constraint_sequences,  # Pass through as-is
        }


# -------------------------
# Enhanced Trainer with Constraint Loss - FIXED VERSION
# -------------------------

class ConstrainedTrainer(transformers.Trainer):
    """Trainer with constraint-aware loss computation"""

    def __init__(self, constraint_weight: float = 0.1, **kwargs):
        super().__init__(**kwargs)
        self.constraint_weight = constraint_weight
        self.model_accepts_loss_kwargs = True

    def compute_loss(self, model, inputs, return_outputs=False, num_items_in_batch=None):
        """Compute loss with constraint penalties"""
        labels = inputs.get("labels")
        constraint_sequences = inputs.get("constraint_sequences", [])

        # Standard forward pass
        outputs = model(**inputs)
        logits = outputs.get("logits")

        # Standard cross-entropy loss
        if labels is not None:
            # Shift so that tokens < n predict n
            shift_logits = logits[..., :-1, :].contiguous()
            shift_labels = labels[..., 1:].contiguous()
            loss_fct = torch.nn.CrossEntropyLoss(ignore_index=IGNORE_INDEX)
            standard_loss = loss_fct(shift_logits.view(-1, shift_logits.size(-1)),
                                     shift_labels.view(-1))
        else:
            standard_loss = outputs.loss

        # Add constraint loss if sequences are available
        constraint_loss = torch.tensor(0.0, device=standard_loss.device)
        if constraint_sequences and any(seq for seq in constraint_sequences):
            constraint_loss = self._compute_constraint_loss(
                shift_logits, shift_labels, constraint_sequences
            )

        total_loss = standard_loss + self.constraint_weight * constraint_loss

        return (total_loss, outputs) if return_outputs else total_loss

    def _compute_constraint_loss(self, logits, labels, constraint_sequences):
        """
        Compute constraint violation penalty using ground truth labels for prefix construction.
        This ensures stable gradient signals and proper constraint learning during training.
        """
        batch_size, seq_len, vocab_size = logits.shape
        constraint_penalty = 0.0

        for batch_idx, sequences in enumerate(constraint_sequences):
            if not sequences:  # No constraints for this sample
                continue

            for pos in range(seq_len):
                if labels[batch_idx, pos] == IGNORE_INDEX:
                    continue

                # CRITICAL FIX: Use ground truth labels for prefix, not predictions
                if pos > 0:
                    # Extract valid label tokens up to current position (excluding IGNORE_INDEX)
                    label_prefix = []
                    for i in range(pos):
                        if labels[batch_idx, i] != IGNORE_INDEX:
                            label_prefix.append(labels[batch_idx, i].item())
                else:
                    label_prefix = []  # No prefix for first position

                # Build constraint trie for this sample
                constraint_trie = ConstraintTrie(sequences, eos_token_id=self.processing_class.eos_token_id, processing_class=self.processing_class)

                # Get valid continuations from current prefix
                valid_tokens = constraint_trie.get_valid_continuations(label_prefix)

                if not valid_tokens:
                    # If no valid continuations, apply maximum penalty
                    constraint_penalty += 10.0  # High penalty for invalid states
                    continue

                # Create constraint mask - only allow valid tokens
                constraint_mask = torch.zeros(vocab_size, device=logits.device)
                for token_id in valid_tokens:
                    if 0 <= token_id < vocab_size:
                        constraint_mask[token_id] = 1.0

                # Compute penalty for violating constraints
                probs = torch.softmax(logits[batch_idx, pos], dim=-1)
                constraint_prob = (probs * constraint_mask).sum()

                # Add penalty: negative log probability of valid tokens
                constraint_penalty += -torch.log(constraint_prob + 1e-8)

        return constraint_penalty / batch_size if batch_size > 0 else torch.tensor(0.0)

    @staticmethod
    def _num_items_from_batch(batch):
        if isinstance(batch, dict):
            for k in ("labels", "input_ids", "pixel_values", "input_values"):
                v = batch.get(k)
                if isinstance(v, torch.Tensor):
                    return int(v.shape[0])
                if isinstance(v, (list, tuple)) and v and isinstance(v[0], torch.Tensor):
                    return int(v[0].shape[0])

            for v in batch.values():
                if isinstance(v, torch.Tensor):
                    return int(v.shape[0])
                if isinstance(v, (list, tuple)) and v and isinstance(v[0], torch.Tensor):
                    return int(v[0].shape[0])

        elif isinstance(batch, (list, tuple)) and batch and isinstance(batch[0], torch.Tensor):
            return int(batch[0].shape[0])

        return 1

    def get_num_items(self, batch):
        # Prefer HF's implementation if present; otherwise use our fallback.
        if hasattr(super(), "get_num_items"):
            try:
                return super().get_num_items(batch)
            except Exception:
                pass

        return self._num_items_from_batch(batch)

    def get_batch_samples(self, epoch_iterator, num_batches, device: torch.device | None = None):
        """
        HF-compatible: returns (batch_samples: List[batch_dict], num_items_in_batch: int)
        Works with both signatures: with or without the 'device' arg.
        """
        batch_samples = []
        for _ in range(num_batches):
            try:
                batch_samples.append(next(epoch_iterator))
            except StopIteration:
                break

        # Prefer token count if labels exist; else fall back to item count
        num_items = None
        if batch_samples and isinstance(batch_samples[0], dict) and "labels" in batch_samples[0]:
            try:
                # If labels are present, counts the number of non-IGNORE_INDEX tokens (i.e., real tokens, not padding)
                # across all batches. This gives a true token count for throughput metrics.
                num_items = sum((b["labels"].ne(IGNORE_INDEX)).sum() for b in batch_samples)
            except Exception:
                num_items = None

        if num_items is None:
            per_mb = [self.get_num_items(b) for b in batch_samples]
            num_items = torch.tensor(sum(per_mb))

        # Ensure CUDA + dense before any gather
        if device is None:
            device = getattr(self, "accelerator", None).device if hasattr(self, "accelerator") else torch.device(
                "cuda" if torch.cuda.is_available() else "cpu")

        if not torch.is_tensor(num_items):
            num_items = torch.tensor(num_items, device=device)
        else:
            num_items = num_items.to(device=device)

        if getattr(num_items, "is_sparse", False):
            num_items = num_items.to_dense()

        if getattr(self.args, "average_tokens_across_devices", False):
            num_items = self.accelerator.gather(num_items).sum().item()
        else:
            num_items = int(num_items.item())

        return batch_samples, num_items

    def get_train_dataloader(self):
        ds = self.train_dataset
        bsz = self.args.per_device_train_batch_size
        ws = torch.distributed.get_world_size() if torch.distributed.is_initialized() else 1
        rk = torch.distributed.get_rank() if torch.distributed.is_initialized() else 0

        # Use a real block size (number of samples per block), not the batch size
        sampler = BlockDistributedSampler(
            dataset=ds,
            batch_size=bsz,
            world_size=ws,
            rank=rk,
            block_size=2048,  # try 512; tune 256–2048 based on storage
            sort_within_block=True,
            seed=self.args.seed,
        )

        num_workers = int(self.args.dataloader_num_workers)

        # Build kwargs so we only pass prefetch_factor/persistent_workers if legal
        loader_kwargs = dict(
            batch_size=bsz,
            sampler=sampler,
            collate_fn=self.data_collator,
            num_workers=num_workers,
            pin_memory=self.args.dataloader_pin_memory,
            drop_last=self.args.dataloader_drop_last,
        )

        if num_workers > 0:
            loader_kwargs["persistent_workers"] = bool(self.args.dataloader_persistent_workers)
            loader_kwargs["prefetch_factor"] = 8  # or 2–4 if you want it lighter in the smoke run
        else:
            # Must be False when num_workers == 0, and DO NOT pass prefetch_factor at all
            loader_kwargs["persistent_workers"] = False

        return DataLoader(ds, **loader_kwargs)


# -------------------------
# Callbacks (unchanged from original)
# -------------------------

class SaveModelCallback(transformers.TrainerCallback):
    def on_save(self, args, state, control, **kwargs):
        if self.trainer.is_world_process_zero():
            print(f"[save] {args.output_dir}/checkpoint-{state.global_step}/")

        if dist.is_available() and dist.is_initialized():
            try:
                if dist.get_backend() == "nccl" and torch.cuda.is_available():
                    dist.barrier(device_ids=[torch.cuda.current_device()])
                else:
                    dist.barrier()
            except Exception:
                # don't let a barrier issue crash training
                pass

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
                    print("------------------")
                    print(msg)
                    print("------------------")
            else:
                # Single process mode
                print("------------------")
                print(msg)
                print("------------------")
        except Exception:
            # Fallback for any distributed issues
            print("------------------")
            print(msg)
            print("------------------")

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
            print(f"\nModel has PEFT: {hasattr(model, 'peft_config')}\n")
            if hasattr(model, 'peft_config'):
                print(f"PEFT config: {model.peft_config}\n")
            if hasattr(model, 'active_adapters'):
                print(f"Active adapters: {model.active_adapters}\n")

            # Check if model is a PeftModel
            print(f"Model type: {type(model)}\n")

            print(f"\n=== SAMPLE GENERATIONS @ step {state.global_step} ===\n")

            for p in self.prompts:
                text_in = p
                inputs = self.tok(text_in, return_tensors="pt").to(model.device)

                with torch.no_grad():
                    out = model.generate(
                        **inputs,
                        min_new_tokens=3,
                        max_new_tokens=self.max_new_tokens,
                        pad_token_id=self.tok.pad_token_id,
                        eos_token_id=self.tok.eos_token_id,
                        **self.gen_kwargs,
                    )

                # Slice off the prompt portion
                gen_tokens = out[0][inputs["input_ids"].shape[1]:]
                text_out = self.tok.decode(gen_tokens, skip_special_tokens=True)

                print(f"Generated token IDs: {out[0].tolist()}\n")
                print(f"Input length: {inputs['input_ids'].shape[1]}\n")
                print(f"Output length: {out[0].shape[0]}\n")
                print(f"New tokens: {out[0][inputs['input_ids'].shape[1]:].tolist()}\n")
                print(f"Prompt: {p}\nOutput: {text_out}\n---")

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
    train_ds = ConstrainedMemmapShardDataset(tokenized_dir, "train")
    # For validation, try to create it but fall back gracefully if no val data
    try:
        eval_ds = ConstrainedMemmapShardDataset(tokenized_dir, "val")
    except FileNotFoundError:
        print("No validation data found, using None for eval_dataset")
        eval_ds = None
    return train_ds, eval_ds


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

    # Load tokenizer (matching the tokenizer used to encode and tokenize the dataset in the `scripts/prompt` folder)
    assert data_args.tokenized_dir, "--tokenized_dir is required and must point to the pre-tokenized shards."
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

    # Load datasets
    train_ds, eval_ds = make_datasets(data_args.tokenized_dir)

    # Tokenizer consistency checks
    assert_meta_compatible(data_args.tokenized_dir, tokenizer, [], "train")
    if eval_ds is not None:
        assert_meta_compatible(data_args.tokenized_dir, tokenizer, [], "val")

    # Enhanced data collator with constraint support
    data_collator = ConstraintAwareDataCollator(
        processing_class=tokenizer,
        pad_to_multiple_of=8,
        static_pad_to=None,
    )

    # Callbacks
    # Add sample generation callback
    sample_prompts = [sample_query_1, sample_query_2, sample_query_3]
    callbacks = [
        SaveModelCallback(),
        LoggingCallback(log_memory=True),
        DelayedEarlyStopping(
            min_early_stopping_epochs=training_args.min_early_stopping_epochs,
            early_stopping_patience=training_args.early_stopping_patience,
        ),
        SampleGenerationCallback(
            tokenizer=tokenizer,
            prompts=sample_prompts,
            every_n_steps=50,
            max_new_tokens=128,
            temperature=0.7,
            do_sample=True,
        )
    ]

    # Enhanced trainer with constraint support
    trainer = ConstrainedTrainer(
        model=model,
        args=training_args,
        train_dataset=train_ds,
        eval_dataset=eval_ds,
        processing_class=tokenizer,
        data_collator=data_collator,
        callbacks=callbacks,
        constraint_weight=training_args.constraint_weight,
    )

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
