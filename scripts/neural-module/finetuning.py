import time, json, logging, glob, bisect
from dataclasses import dataclass, field
from typing import Dict, Optional, List, Tuple

from safetensors.torch import save_model
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
from transformers import Trainer
from transformers import BitsAndBytesConfig
from torch.utils.data import DataLoader

from peft.utils import prepare_model_for_kbit_training
from peft import get_peft_model, LoraConfig

IGNORE_INDEX = -100
sample_query_1 = "// Test prefix\npublic void testFromJson() throws IOException {\n    String json = \"{\\n\" + \"    \\\"task_routing\\\": {\\n\" + \"        \\\"filters\\\": [{\\n\" + \"            \\\"expression\\\": \\\"skill == \\\\\\\"HR\\\\\\\"\\\",\\n\" + \"            \\\"friendlyName\\\": \\\"4354\\\",\\n\" + \"            \\\"targets\\\": [{\\n\" + \"                \\\"queue\\\": \\\"WQaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\\\",\\n\" + \"                \\\"priority\\\": 54333,\\n\" + \"                \\\"timeout\\\": 30\\n\" + \"            }]\\n\" + \"        }],\\n\" + \"        \\\"default_filter\\\": {\\n\" + \"            \\\"queue\\\": \\\"WQaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\\\"\\n\" + \"        }\\n\" + \"    }\\n\" + \"}\";\n    Workflow workflow = Workflow.fromJson(json);\n    WorkflowRuleTarget defaultTarget = workflow.getDefaultTarget();\n    assertEquals(\"WQaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\", defaultTarget.getQueue());\n    List<WorkflowRule> rules = workflow.getWorkflowRules();\n    assertEquals(1, rules.size());\n    WorkflowRule rule = rules.get(0);\n    assertEquals(\"skill == \\\"HR\\\"\",<mask_id>\n}\n\n// Junit Version\nJUnit 4\n\n// Next candidate project tokens\n[ WorkflowRuleTargetTestSTARSplitTest, TaskRouterResource, WorkflowTestSTARSplitTestPrefix, WorkflowRuleTarget, Workflow, IOException, TaskRouting, WorkflowTestSTARNormalizedTest, WorkflowRuleTargetTestSTARSplitTestPrefix, WorkflowRuleTargetTestSTARNormalizedTest, Assert, WorkflowTestSTARSplitTest, WorkflowRule, Collections, WorkflowRuleTargetTest, ObjectMapper, WorkflowTest, getClass, wait, hashCode, equals, notifyAll, clone, toString, finalize, registerNatives, notify, defaultTarget, json, rule, rules, workflow ]\n// Next candidate Java tokens\n[ -, @, boolean, byte, char, double, float, int, long, new, short, super, switch, this, void, module, open, requires, exports, opens, to, uses, provides, with, transitive, var, yield, record, sealed, permits, true, false, TEXT_BLOCK, null, (, <, !, ~, ++, --, + ]\n// Next candidate generic tokens\n[ DECIMAL_LITERAL, HEX_LITERAL, OCT_LITERAL, BINARY_LITERAL, FLOAT_LITERAL, HEX_FLOAT_LITERAL, CHAR_LITERAL, STRING_LITERAL ]\n\n// Invoked methods in test prefix\n/**\n * Converts a JSON workflow configuration to a workflow object.\n *\n * @param json JSON for workflow\n * @return a workflow rule target object\n * @throws IOException if unable to create object\n */\npublic static Workflow fromJson(String json) throws IOException{\n    ObjectMapper mapper = new ObjectMapper();\n    return mapper.readValue(json, Workflow.class);\n}\n\npublic WorkflowRuleTarget getDefaultTarget(){\n    return taskRouting.getDefaultTarget();\n}\n\n/**\n * Get the queue for the workflow rule target.\n *\n * @return queue sid\n */\npublic String getQueue(){\n    return queue;\n}\n\npublic List<WorkflowRule> getWorkflowRules(){\n    return taskRouting.getWorkflowRules();\n}\n\n/**\n * Returns the number of elements in this list.  If this list contains\n * more than <tt>Integer.MAX_VALUE</tt> elements, returns\n * <tt>Integer.MAX_VALUE</tt>.\n *\n * @return the number of elements in this list\n */\nint size()\n\n// Constructors and methods defined in focal class\npublic List<WorkflowRule> getWorkflowRules(){\n    return taskRouting.getWorkflowRules();\n}\n\npublic WorkflowRuleTarget getDefaultTarget(){\n    return taskRouting.getDefaultTarget();\n}\n\n/**\n * Converts a JSON workflow configuration to a workflow object.\n *\n * @param json JSON for workflow\n * @return a workflow rule target object\n * @throws IOException if unable to create object\n */\npublic static Workflow fromJson(String json) throws IOException{\n    ObjectMapper mapper = new ObjectMapper();\n    return mapper.readValue(json, Workflow.class);\n}\n\n// Fields defined in focal class\nprivate final TaskRouting taskRouting;"
sample_query_2 = "// Test prefix\npublic void testToJson() throws IOException {\n    List<WorkflowRuleTarget> targets = Collections.singletonList(new WorkflowRuleTarget.Builder(\"WQaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\").priority(54333).timeout(30).build());\n    List<WorkflowRule> rules = Collections.singletonList(new WorkflowRule.Builder(\"skill == \\\"HR\\\"\", targets).friendlyName(\"4354\").build());\n    WorkflowRuleTarget defaultTarget = new WorkflowRuleTarget.Builder(\"WQaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\").build();\n    Workflow workflow = new Workflow(rules, defaultTarget);\n    assertEquals(\"{\\\"task_routing\\\":{\\\"filters\\\":[{\\\"expression\\\":\\\"skill == \\\\\\\"HR\\\\\\\"\\\",\\\"targets\\\":[{\\\"queue\\\":\\\"WQaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\\\",\\\"priority\\\":54333,\\\"timeout\\\":30}],\\\"friendlyName\\\":\\\"4354\\\"}],\\\"default_filter\\\":{\\\"queue\\\":\\\"WQaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\\\"}}}\",workflow.<mask_id>\n}\n\n// Junit Version\nJUnit 4\n\n// Next candidate project tokens\n[ getClass, wait, notifyAll, registerNatives, notify, getDefaultTarget, toJson, hashCode, equals, clone, getWorkflowRules, fromJson, toString, finalize, taskRouting ]\n// Next candidate Java tokens\n[ super, class, this, module, open, requires, exports, opens, to, uses, <, provides, with, @, transitive, var, yield, record, new, sealed, permits ]\n// Next candidate generic tokens\n[  ]\n\n// Invoked methods in test prefix\n/**\n * Returns an immutable list containing only the specified object.\n * The returned list is serializable.\n *\n * @param  <T> the class of the objects in the list\n * @param o the sole object to be stored in the returned list.\n * @return an immutable list containing only the specified object.\n * @since 1.3\n */\npublic static <T> List<T> singletonList(T o){\n    return new SingletonList<>(o);\n}\n\npublic WorkflowRuleTarget build(){\n    return new WorkflowRuleTarget(this);\n}\n\npublic Builder timeout(Integer timeout){\n    this.timeout = timeout;\n    return this;\n}\n\npublic Builder priority(Integer priority){\n    this.priority = priority;\n    return this;\n}\n\npublic Builder(String queue){\n    this.queue = queue;\n}\n\npublic WorkflowRule build(){\n    return new WorkflowRule(this);\n}\n\npublic Builder friendlyName(String friendlyName){\n    this.friendlyName = friendlyName;\n    return this;\n}\n\npublic Builder(String expression, List<WorkflowRuleTarget> targets){\n    this.expression = expression;\n    this.targets = targets;\n}\n\n/**\n * Define a workflow.\n *\n * @param workflowRules list of workflow rules (in order they will be processed)\n * @param defaultTarget default filter\n */\npublic Workflow(List<WorkflowRule> workflowRules, WorkflowRuleTarget defaultTarget){\n    taskRouting = new TaskRouting(workflowRules, defaultTarget);\n}\n\n// Constructors and methods defined in focal class\npublic List<WorkflowRule> getWorkflowRules(){\n    return taskRouting.getWorkflowRules();\n}\n\npublic WorkflowRuleTarget getDefaultTarget(){\n    return taskRouting.getDefaultTarget();\n}\n\n/**\n * Converts a JSON workflow configuration to a workflow object.\n *\n * @param json JSON for workflow\n * @return a workflow rule target object\n * @throws IOException if unable to create object\n */\npublic static Workflow fromJson(String json) throws IOException{\n    ObjectMapper mapper = new ObjectMapper();\n    return mapper.readValue(json, Workflow.class);\n}\n\n// Fields defined in focal class\nprivate final TaskRouting taskRouting;"
sample_query_3 = "// Test prefix\npublic void testXmlAttributesDeserialization() {\n    final Redirect elem = new Redirect.Builder(URI.create(\"https://example.com\")).method(HttpMethod.GET).build();\n    assertEquals(Redirect.Builder.fromXml(\"<Redirect method=\\\"GET\\\">https://example.com</Redirect>\").build().toXml(),<mask_id>\n}\n\n// Junit Version\nJUnit 4\n\n// Next candidate project tokens\n[ MediaTestSTARNormalizedTest, Message, HashMap, MessageTest, URI, RedirectTestSTARNormalizedTest, MediaTestSTARSplitTest, Promoter, TwiML, Assert, RedirectTest, MessageTestSTARNormalizedTest, BodyTestSTARSplitTestPrefix, Body, JsonProcessingException, BodyTest, MessageTestSTARSplitTestPrefix, MessageTestSTARSplitTest, BodyTestSTARNormalizedTest, Media, RedirectTestSTARSplitTest, Redirect, TwiMLException, MediaTest, BodyTestSTARSplitTest, MediaTestSTARSplitTestPrefix, RedirectTestSTARSplitTestPrefix, HttpMethod, getClass, wait, hashCode, notifyAll, equals, clone, finalize, toString, registerNatives, notify, elem ]\n// Next candidate Java tokens\n[ -, @, boolean, byte, char, double, float, int, long, new, short, super, switch, this, void, module, open, requires, exports, opens, to, uses, provides, with, transitive, var, yield, record, sealed, permits, true, false, TEXT_BLOCK, null, (, <, !, ~, ++, --, + ]\n// Next candidate generic tokens\n[ DECIMAL_LITERAL, HEX_LITERAL, OCT_LITERAL, BINARY_LITERAL, FLOAT_LITERAL, HEX_FLOAT_LITERAL, CHAR_LITERAL, STRING_LITERAL ]\n\n// Invoked methods in test prefix\n/**\n * Create and return resulting {@code <Redirect>} element\n */\npublic Redirect build(){\n    return new Redirect(this);\n}\n\n/**\n * Redirect URL method\n */\npublic Builder method(HttpMethod method){\n    this.method = method;\n    return this;\n}\n\n/**\n * Creates a URI by parsing the given string.\n *\n * <p> This convenience factory method works as if by invoking the {@link\n * #URI(String)} constructor; any {@link URISyntaxException} thrown by the\n * constructor is caught and wrapped in a new {@link\n * IllegalArgumentException} object, which is then thrown.\n *\n * <p> This method is provided for use in situations where it is known that\n * the given string is a legal URI, for example for URI constants declared\n * within in a program, and so it would be considered a programming error\n * for the string not to parse as such.  The constructors, which throw\n * {@link URISyntaxException} directly, should be used situations where a\n * URI is being constructed from user input or from some other source that\n * may be prone to errors.  </p>\n *\n * @param  str   The string to be parsed into a URI\n * @return The new URI\n *\n * @throws  NullPointerException\n *          If {@code str} is {@code null}\n *\n * @throws  IllegalArgumentException\n *          If the given string violates RFC&nbsp;2396\n */\npublic static URI create(String str){\n    try {\n        return new URI(str);\n    } catch (URISyntaxException x) {\n        throw new IllegalArgumentException(x.getMessage(), x);\n    }\n}\n\n/**\n * Create a {@code <Redirect>} with url\n */\npublic Builder(URI url){\n    this.url = url;\n}\n\n// Constructors and methods defined in focal class\n/**\n * The body of the TwiML element\n *\n * @return Element body as a string if present else null\n */\nprotected String getElementBody(){\n    return this.getUrl() == null ? null : this.getUrl().toString();\n}\n\n/**\n * Attributes to set on the generated XML element\n *\n * @return A Map of attribute keys to values\n */\nprotected Map<String, String> getElementAttributes(){\n    // Preserve order of attributes\n    Map<String, String> attrs = new HashMap<>();\n    if (this.getMethod() != null) {\n        attrs.put(\"method\", this.getMethod().toString());\n    }\n    return attrs;\n}\n\n/**\n * Redirect URL method\n *\n * @return Redirect URL method\n */\npublic HttpMethod getMethod(){\n    return method;\n}\n\n/**\n * Redirect URL\n *\n * @return Redirect URL\n */\npublic URI getUrl(){\n    return url;\n}\n\n// Fields defined in focal class\nprivate final HttpMethod method;\n\nprivate final URI url;\n\nprivate HttpMethod method;\n\nprivate URI url;"
logging.basicConfig(level=logging.INFO)

# -------------------------
# Args
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
        metadata={"help":"Opening token for a message block (fallback formatter)."}
    )
    end_id: Optional[str] = field(
        default="<|im_end|>",
        metadata={"help":"Closing token for a message block (fallback formatter)."}
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
    seed : int = 42

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
                    blk = gidx[i : i + self.block_size]
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
        rank_blocks = blocks[self.rank :: self.ws]

        # Flatten indices for this rank
        idx = np.concatenate(rank_blocks, axis=0)

        # Ensure equal number of batches on every rank
        if self.bs > 1:
            idx = idx[: (len(idx) // self.bs) * self.bs]

        return iter(idx.tolist())

    def __len__(self) -> int:
        return self._len


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
        tok.add_special_tokens({"additional_special_tokens": special_tokens})
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
# Data: memmap shards
# -------------------------

class MemmapShardDataset(Dataset):
    """
    Map-style dataset over pre-tokenized variable-length sequences, with random/shuffled
    sampling handled by Trainer's (Distributed)Sampler. This ensures each epoch traverses
    the entire dataset exactly once per process.

    Directory layout (tokenized_dir):
      train.00000.input_ids.bin
      train.00000.labels.bin
      train.00000.idx
      train.00001.input_ids.bin
      ...
      train.meta.json
      val.00000.input_ids.bin
      ...
      val.meta.json
      [optional] tokenizer/  (saved tokenizer from builder)

    MemmapShardDataset is designed for efficient handling of large, pre-tokenized datasets for language model training.
    In particular:
        - Efficiently load and access very large datasets (potentially billions of tokens) that do not fit in RAM,
          by using memory-mapped files.
        - Support variable-length sequences stored in multiple shards, with fast indexing via offset files.
        - Enable distributed training by allowing random access and shuffling, so each process can sample
          data independently.
    Data is split into multiple shards (chunks), each with three files:
        - .input_ids.bin: int32 array of concatenated token IDs for all samples in the shard.
        - .labels.bin: int32 array of concatenated label IDs (shifted input IDs) for all samples in the shard.
        - .idx: uint64 array of offsets into the above two files, with N+1 entries for N samples.
          The i-th sample's data is found between offsets[i] and offsets[i+1].
    """
    def  __init__(self, root: str, split: str):
        self.root = root
        self.split = split
        self.shards: List[Dict] = []
        self.cum_counts: List[int] = []
        # Collect and sort the .idx shard paths
        idx_paths = sorted(glob.glob(os.path.join(root, f"{split}.*.idx")))
        # If no shards found, raise an error
        if not idx_paths:
            raise FileNotFoundError(f"No {split} shards (.idx) found in {root}")
        # Load each shard's offsets and compute lengths
        lengths_chunks: List[np.ndarray] = []
        total = 0
        # Iterate over each shard's .idx file
        for idx_path in idx_paths:
            # Get the corresponding .bin paths for input_ids and labels
            base = os.path.splitext(idx_path)[0]
            ids_path = base + ".input_ids.bin"
            lbl_path = base + ".labels.bin"
            # Load offsets (N+1 entries)
            offsets = np.fromfile(idx_path, dtype=np.uint64)
            if offsets.size < 2:
                continue
            n_samples = offsets.size - 1
            self.shards.append({
                "base": base,
                "ids_path": ids_path,
                "lbl_path": lbl_path,
                "idx_path": idx_path,
                "offsets": offsets,   # small; keep in RAM
                "n": n_samples,
            })
            # Length computation (tokens per sample)
            lengths = np.diff(offsets).astype(np.int32)  # safe since max_length <= 8192
            lengths_chunks.append(lengths)
            total += n_samples
            self.cum_counts.append(total)
        if total == 0:
            raise RuntimeError(f"{split}: zero samples found in {root}")
        # Concatenate all per-shard lengths into one array aligned with global indices
        self.lengths: np.ndarray = np.concatenate(lengths_chunks, axis=0)  # shape = [__len__()]
        # Per-worker cache for memmaps (opened lazily)
        self._ids_mm = [None] * len(self.shards)
        self._lbl_mm = [None] * len(self.shards)

    def __len__(self) -> int:
        return self.cum_counts[-1]

    def _open_mm(self, shard_idx: int):
        if self._ids_mm[shard_idx] is None:
            ids_path = self.shards[shard_idx]["ids_path"]
            lbl_path = self.shards[shard_idx]["lbl_path"]
            self._ids_mm[shard_idx] = np.memmap(ids_path, mode="r", dtype=np.int32)
            self._lbl_mm[shard_idx] = np.memmap(lbl_path, mode="r", dtype=np.int32)

    def _locate(self, i: int) -> Tuple[int, int]:
        # map global index -> (shard_idx, local_idx)
        shard_idx = bisect.bisect_right(self.cum_counts, i)
        prev_total = 0 if shard_idx == 0 else self.cum_counts[shard_idx-1]
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
        return {"input_ids": ids, "labels": lbl}


@dataclass
class DataCollatorForSupervisedDataset:
    tokenizer: transformers.PreTrainedTokenizer
    pad_to_multiple_of: int = 8
    static_pad_to: Optional[int] = None  # keep None for dynamic lengths

    def __call__(self, instances: List[Dict]) -> Dict[str, torch.Tensor]:
        input_ids = [ex["input_ids"] for ex in instances]
        labels    = [ex["labels"]    for ex in instances]

        ids = torch.nn.utils.rnn.pad_sequence(
            input_ids, batch_first=True, padding_value=self.tokenizer.pad_token_id
        )
        lbl = torch.nn.utils.rnn.pad_sequence(
            labels, batch_first=True, padding_value=IGNORE_INDEX
        )

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
                ids = torch.nn.functional.pad(ids, (0, pad), value=self.tokenizer.pad_token_id)
                lbl = torch.nn.functional.pad(lbl, (0, pad), value=IGNORE_INDEX)

        return {
            "input_ids": ids,
            "labels": lbl,
            "attention_mask": ids.ne(self.tokenizer.pad_token_id),
        }

# -------------------------
# Logging + delayed early stop
# -------------------------

class LocalityTrainer(Trainer):
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
            device = getattr(self, "accelerator", None).device if hasattr(self, "accelerator") else torch.device("cuda" if torch.cuda.is_available() else "cpu")
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
            block_size=2048,            # try 512; tune 256–2048 based on storage
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
        if logs is None: return
        now = time.time()
        world = getattr(args, "world_size", 1)
        if self.last_time is None: self.last_time = now

        include_tok_seen = getattr(args, "include_num_input_tokens_seen", False)
        if include_tok_seen and getattr(state, "num_input_tokens_seen", None) is not None:
            delta_tokens = (state.num_input_tokens_seen - self.total_tokens_seen) * world
            self.total_tokens_seen = state.num_input_tokens_seen
        else:
            steps_elapsed = state.global_step - self.last_step
            est_tokens_per_step = args.per_device_train_batch_size * args.model_max_length * world
            delta_tokens = est_tokens_per_step * max(0, steps_elapsed)
            self.last_step = state.global_step

        dt = max(1e-6, now - self.last_time)
        tps = int(delta_tokens / dt)

        msg = {"step": state.global_step, "epoch": state.epoch,
               "loss": logs.get("loss"), "eval_loss": logs.get("eval_loss"),
               "lr": logs.get("learning_rate"), "tokens_per_sec": tps}
        if self.log_memory and torch.cuda.is_available():
            try:
                mem = torch.cuda.max_memory_allocated() // 1_000_000
                msg["gpu_mem_MiB"] = int(mem)
                torch.cuda.reset_peak_memory_stats()
            except Exception:
                pass
        if self.trainer.is_world_process_zero():
            print("\n------------------")
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
            print(f"Model has PEFT: {hasattr(model, 'peft_config')}")
            if hasattr(model, 'peft_config'):
                print(f"PEFT config: {model.peft_config}")
                if hasattr(model, 'active_adapters'):
                    print(f"Active adapters: {model.active_adapters}")

            # Check if model is a PeftModel
            print(f"Model type: {type(model)}")

            print(f"\n=== SAMPLE GENERATIONS @ step {state.global_step} ===")
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
                print(f"Generated token IDs: {out[0].tolist()}")
                print(f"Input length: {inputs['input_ids'].shape[1]}")
                print(f"Output length: {out[0].shape[0]}")
                print(f"New tokens: {out[0][inputs['input_ids'].shape[1]:].tolist()}")
                print(f"Prompt: {p}\nOutput: {text_out}\n---")
        finally:
            model.config.use_cache = prev_use_cache
            if was_train:
                model.train()


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
    train_ds = MemmapShardDataset(tokenized_dir, "train")
    eval_ds  = MemmapShardDataset(tokenized_dir, "val")
    return train_ds, eval_ds

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
        special_tokens=["<|fill_mask_token|>", "<|type_mask_token|>", "<|value_mask_token|>"] #[model_args.start_id, model_args.end_id]
    )
    # Model (QLoRA 4-bit)
    #
    # LoRA is a parameter-efficient fine-tuning technique for large language models.
    # Instead of updating all model weights during fine-tuning, LoRA freezes the original weights and injects
    # small, trainable low-rank matrices (adapters) into certain layers (usually attention and feed-forward layers).
    # This drastically reduces the number of trainable parameters and memory usage, while maintaining
    # performance comparable to full fine-tuning.
    #
    # QLoRA extends LoRA by combining it with 4-bit quantization of the base model weights.
    # Quantization means representing weights with fewer bits (here, 4 bits instead of 16 or 32),
    # which saves memory and allows you to run/fine-tune very large models on consumer GPUs.
    #
    # - The base model weights are quantized to 4 bits (very compact).
    # - LoRA adapters are added and trained in normal precision (e.g., 16 or 32 bits).
    #
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
    # Ensure model embeddings match tokenizer (in case additional specials were added)
    model.resize_token_embeddings(len(tokenizer), pad_to_multiple_of=8)
    model.config.vocab_size = model.get_input_embeddings().weight.shape[0]
    model.config.pad_token_id = tokenizer.pad_token_id
    model.config.eos_token_id = tokenizer.eos_token_id
    model.config.bos_token_id = tokenizer.bos_token_id
    model = prepare_model_for_kbit_training(model, use_gradient_checkpointing=True)
    if hasattr(model, "generation_config"):
        model.generation_config.pad_token_id = tokenizer.pad_token_id
        model.generation_config.eos_token_id = tokenizer.eos_token_id
        model.generation_config.bos_token_id = tokenizer.bos_token_id
    model.gradient_checkpointing_enable(gradient_checkpointing_kwargs={"use_reentrant": False})
    model.config.use_cache = False
    # PEFT (Parameter-Efficient Fine-Tuning) is a family of methods that allow to adapt large pre-trained models
    # to new tasks by training only a small subset of parameters, rather than the entire model.
    # LoRA (Low-Rank Adaptation) is one such PEFT method that injects trainable low-rank matrices into
    # certain layers of the model (typically attention and feed-forward layers) while keeping the original
    # model weights frozen. This drastically reduces the number of trainable parameters and memory usage,
    # while maintaining performance comparable to full fine-tuning.
    # Here, we configure LoRA to target all linear layers in the model:
    #    - r=16: The rank of the low-rank matrices. Higher rank means more capacity for adaptation,
    #    but more trainable parameters.
    #    - lora_alpha=32: A scaling factor that amplifies the effect of the low-rank updates.
    #    - lora_dropout=0.05: Dropout rate applied to the LoRA layers for regularization.
    #    - bias="none": No additional bias parameters are added.
    #    - task_type="CAUSAL_LM": Specifies that the model is being fine-tuned for a causal language modeling task.
    #    - target_modules=None: This means LoRA will be applied to all linear layers in the model.
    if training_args.use_peft:
        peft_config = LoraConfig(
            r=16,
            lora_alpha=32,
            lora_dropout=0.05,
            bias="none",
            task_type="CAUSAL_LM",
            target_modules=None,
        )
        model = get_peft_model(model, peft_config)
        model.print_trainable_parameters()
    # Sanity-check tokenizer vs meta
    try:
        assert_meta_compatible(
            data_args.tokenized_dir,
            tokenizer,
            [ model_args.start_id, model_args.end_id ],
            "train"
        )
        assert_meta_compatible(
            data_args.tokenized_dir,
            tokenizer,
            [ model_args.start_id, model_args.end_id ],
            "val"
        )
    except AssertionError as e:
        raise RuntimeError(f"Tokenizer/meta mismatch: {e}")
    # Datasets
    train_ds, eval_ds = make_datasets(data_args.tokenized_dir)
    # Collator (dynamic padding to multiple of 8)
    data_collator = DataCollatorForSupervisedDataset(
        tokenizer=tokenizer,
        pad_to_multiple_of=8,
        static_pad_to=None
    )
    # Trainer
    logging_callback = LoggingCallback(log_memory=True)
    save_model_callback = SaveModelCallback()
    delayed_early_stopping = DelayedEarlyStopping(
        min_early_stopping_epochs=training_args.min_early_stopping_epochs,
        early_stopping_patience=training_args.early_stopping_patience
    )
    sample_generation_callback = SampleGenerationCallback(
            tokenizer,
            prompts=[
                sample_query_1,
                sample_query_2,
                sample_query_3,
            ],
            every_n_steps=5,
            max_new_tokens=64,
        )
    callbacks = [
        logging_callback,
        save_model_callback,
        delayed_early_stopping,
        sample_generation_callback,
    ]
    trainer = LocalityTrainer(
        model=model,
        args=training_args,
        processing_class=tokenizer,
        train_dataset=train_ds,
        eval_dataset=eval_ds,
        data_collator=data_collator,
        callbacks=callbacks,
    )
    logging_callback.trainer = trainer
    save_model_callback.trainer = trainer
    sample_generation_callback.trainer = trainer
    # Save base model info for reproducibility
    if trainer.is_world_process_zero():
        info = {
            "base_model_name_or_path": model_args.model_name_or_path,
            "base_model_revision": used_revision,
            "resolved_load_path": base_load_path,
        }
        with open(os.path.join(training_args.output_dir, "meta_base_model.json"), "w") as f:
            json.dump(info, f, indent=2)
    trainer.train()
    trainer.save_state()
    trainer.save_model(output_dir=training_args.output_dir)
    tokenizer.save_pretrained(training_args.output_dir)
    # Persist generation config for inference tools that read it
    try:
        model.generation_config.save_pretrained(training_args.output_dir)
    except Exception:
        pass
    # Save LoRA adapter folder and embed the revision inside adapter_config.json (optional, recommended)
    lora_dir = training_args.output_dir + "_lora"
    model.save_pretrained(lora_dir)
    tokenizer.save_pretrained(lora_dir)
    if trainer.is_world_process_zero():
        try:
            ac_path = os.path.join(lora_dir, "adapter_config.json")
            cfg = json.load(open(ac_path))
            cfg["base_model_name_or_path"] = model_args.model_name_or_path
            cfg["base_model_revision"] = used_revision  # <-- commit SHA / 'local'
            json.dump(cfg, open(ac_path, "w"), indent=2)
        except Exception:
            pass

if __name__ == "__main__":
    train()
