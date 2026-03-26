"""
Context Retrieval Module for RAG-based Dynamic Context Integration.

This module provides functionality to:
- Load and store code snippets from repositories
- Generate semantic embeddings for code snippets using Qwen models
- Retrieve semantically similar code based on queries
- Chunk long code files into token-sized segments with overlap
- Integrate retrieved context into prompt generation
"""

import os
import pickle
import logging
import numpy as np
import copy
from typing import List, Dict, Any, Optional, Tuple, Callable, Sequence
from dataclasses import dataclass
from pathlib import Path
import json
import re

from transformers import AutoTokenizer, AutoModel
from tree_sitter import Language, Parser, Node

# Tree-sitter language pack for Java
try:
    from tree_sitter_language_pack import get_parser
except ImportError:
    get_parser = None


@dataclass
class CodeSnippet:
    """Represents a code snippet with metadata."""

    content: str
    filepath: str
    language: str = "java"
    function_name: Optional[str] = None
    class_name: Optional[str] = None
    metadata: Dict[str, Any] = None
    chunks: Optional[List[str]] = None  # AST or token-chunked version for long files
    chunk_count: int = 0  # Number of chunks (0 if not chunked)

    def __post_init__(self):
        self.metadata = self.metadata or {}
        # Auto-chunk if content is too long for the model
        if len(self.content) > 2000:  # Threshold to trigger chunking
            self._chunk_content()

    def _chunk_content(self):
        """
        Split long content into smaller chunks for token-efficient processing.
        Uses AST-based chunking if tree-sitter is available, otherwise falls back to token/character-based.
        """
        if self.chunks is not None:
            return  # Already chunked

        chunks = self._ast_based_chunking()
        if chunks:
            self.chunks = chunks
            self.chunk_count = len(chunks)
            self.content = chunks[0] if chunks else self.content
            return

    def _ast_based_chunking(self) -> Optional[List[str]]:
        """
        Chunk content using AST parsing with awareness of class structure.
        Uses tree-sitter to extract classes, methods, and maintain semantic boundaries.
        """
        try:
            # Parse code using tree-sitter
            parser = get_parser(self.language)
            tree = parser.parse(bytes(self.content, "utf-8"))

            # Check if parsing was successful
            if (
                not tree.root_node.children
                or tree.root_node.children[0].type == "ERROR"
            ):
                logging.warning(f"Failed to parse code with language {self.language}")
                return None

            # Chunk the AST
            chunk_dicts = self._chunk_node(tree.root_node, self.content)
            chunks = [chunk_dict["text"] for chunk_dict in chunk_dicts]

            # Clean up chunks
            chunks = [chunk.strip() for chunk in chunks if chunk.strip()]

            if chunks:
                logging.debug(
                    f"AST-based chunking: {len(self.content)} chars -> {len(chunks)} chunks"
                )
                return chunks

            return None

        except Exception as e:
            logging.warning(
                f"AST-based chunking failed: {e}, falling back to token-based"
            )
            return None

    def _chunk_node(
        self,
        node: Node,
        text: str,
        last_end: int = 0,
        current_chunk: Optional[dict] = None,
        new_chunks: Optional[List[dict]] = None,
    ) -> List[dict]:
        """
        Recursively chunk an AST node into smaller code snippets based on character limits and AST type.

        This follows the pattern from the user's ASTCodeSplitter.
        """
        if current_chunk is None:
            current_chunk = {"text": "", "metadata": {"abstract_syntax_names": set()}}
        if new_chunks is None:
            new_chunks = []

        # Skip too long comments
        if (
            node.type == "block_comment"
            and node.byte_range[1] - node.byte_range[0] > 4 * 1024
        ):
            return new_chunks

        # Get max chunk size from metadata
        max_chars = self.metadata.get("max_chunk_size", 1024 * 4)

        for child in node.children:
            # Skip too long comments
            if (
                child.type == "block_comment"
                and child.byte_range[1] - child.byte_range[0] > 4 * 1024
            ):
                last_end = child.end_byte
                continue

            # Child is too big, recursively chunk it
            if child.end_byte - child.start_byte > max_chars:
                # If current chunk has content, save it first
                if len(current_chunk["text"]) > 0:
                    new_chunks.append(current_chunk)

                # Start new chunk with this child
                current_chunk = {
                    "text": "",
                    "metadata": {"abstract_syntax_names": set()},
                }
                new_chunks.extend(
                    self._chunk_node(child, text, last_end, current_chunk, new_chunks)
                )
            # Child would make current chunk too big, start new chunk
            elif (
                len(current_chunk["text"]) + child.end_byte - child.start_byte
                > max_chars
            ):
                new_chunks.append(current_chunk)
                current_chunk = {
                    "text": "",
                    "metadata": {"abstract_syntax_names": set()},
                }
                current_chunk["text"] = text[last_end : child.end_byte]
                self._inject_metadata(child, current_chunk, text)
            # Otherwise, add child to current chunk
            else:
                current_chunk["text"] += text[last_end : child.end_byte]
                self._inject_metadata(child, current_chunk, text)

            last_end = child.end_byte

        # Add any remaining content to current chunk
        if len(current_chunk["text"]) > 0:
            new_chunks.append(current_chunk)

        return new_chunks

    def _inject_metadata(self, node: Node, current_chunk: dict, text: str):
        """
        Injects AST metadata to a processing chunk.
        """
        current_chunk["metadata"]["abstract_syntax_names"].add(node.type)

        if node.type == "class_declaration":
            # Get class name
            for child in node.children:
                if child.type == "identifier":
                    current_chunk["metadata"]["class_name"] = text[
                        child.start_byte : child.end_byte
                    ]
                    break
            # Collect method signatures within class
            method_signatures = []
            self._collect_method_signatures(node, text, method_signatures)
            if method_signatures:
                current_chunk["metadata"]["methods"] = method_signatures
        elif node.type in ["method_declaration", "constructor_declaration"]:
            # Extract method signature
            start_byte = node.children[0].start_byte
            end_byte = None
            for child in node.children:
                if "body" in child.type:
                    end_byte = child.start_byte
                    break
            if end_byte:
                method_signature = text[start_byte:end_byte].strip()
                current_chunk["metadata"]["methods"] = current_chunk["metadata"].get(
                    "methods", []
                )
                current_chunk["metadata"]["methods"].append(method_signature)

    def _collect_method_signatures(
        self, node: Node, text: str, method_signatures: List[str]
    ):
        """
        Recursively collects method signatures within a class.
        """
        for child in node.children:
            if child.type in ["method_declaration", "constructor_declaration"]:
                start_byte = child.children[0].start_byte
                end_byte = None
                for grandchild in child.children:
                    if "body" in grandchild.type:
                        end_byte = grandchild.start_byte
                        break
                if end_byte:
                    method_signature = text[start_byte:end_byte].strip()
                    method_signatures.append(method_signature)
            self._collect_method_signatures(child, text, method_signatures)


class CodeSnippetStore:
    """
    Manages a collection of code snippets and their embeddings.
    """

    def __init__(
        self,
        code_directory: str,
        max_snippet_length: int = 500,
        cache_embeddings: bool = True,
        cache_file: Optional[str] = None,
        embedding_model_name: str = "Qwen/Qwen3-Embedding-4B",
        chunk_size: int = 1024 * 4,
        chunk_overlap: int = 512,
        embedding_dimension: int = 2048,
    ):
        """
        Initialize the code snippet store.

        Args:
            code_directory: Path to the directory containing source code files
            max_snippet_length: Maximum length of code snippets to store (fallback)
            cache_embeddings: Whether to cache embeddings for reuse
            cache_file: Path to cache file for storing embeddings
            embedding_model_name: Name of the embedding model to use (Qwen only)
            chunk_size: Maximum number of characters per chunk for AST-based chunking
            chunk_overlap: Number of overlapping characters between chunks
            embedding_dimension: Dimension of embeddings (2048 for Qwen)
        """
        self.code_directory = Path(code_directory)
        self.max_snippet_length = max_snippet_length
        self.cache_embeddings = cache_embeddings
        self.embedding_model_name = embedding_model_name
        self.chunk_size = chunk_size
        self.chunk_overlap = chunk_overlap
        self.embedding_dimension = embedding_dimension
        self.cache_file = None
        self.snippets: List[CodeSnippet] = []
        self.embeddings = None
        self.embedding_model = None
        self.tokenizer = None
        self._model_loaded = False

        # Create embeddings cache file path if not provided
        if cache_file is None:
            if cache_embeddings:
                self.cache_file = Path(code_directory).parent / "embeddings_cache.pkl"
        else:
            self.cache_file = Path(cache_file)

    def load_snippets(self) -> int:
        """
        Load all code snippets from the repository directory.

        Returns:
            Number of snippets loaded
        """
        if not self.code_directory.exists():
            logging.warning(f"Code directory not found: {self.code_directory}")
            return 0

        snippet_count = 0

        # Supported file extensions
        supported_extensions = {".java", ".kt", ".py", ".js", ".ts"}

        for file_path in self.code_directory.rglob("*"):
            if file_path.suffix in supported_extensions and file_path.is_file():
                try:
                    with open(file_path, "r", encoding="utf-8") as f:
                        content = f.read()

                    # Truncate snippet if too long
                    if len(content) > self.max_snippet_length:
                        content = content[: self.max_snippet_length]

                    # Extract metadata from filepath
                    relative_path = file_path.relative_to(self.code_directory)
                    filepath = str(relative_path)

                    # Extract class/function names if possible
                    class_name = None
                    function_name = None

                    if file_path.suffix == ".java":
                        # Try to extract class name
                        for line in content.split("\n"):
                            if line.strip().startswith("class "):
                                class_name = (
                                    line.strip()
                                    .split("class ")[1]
                                    .split("{")[0]
                                    .strip()
                                )
                                break

                    # Prepare metadata for chunking
                    metadata = {
                        "full_path": str(file_path),
                        "max_chunk_size": self.chunk_size,
                        "chunk_overlap": self.chunk_overlap,
                    }

                    snippet = CodeSnippet(
                        content=content,
                        filepath=filepath,
                        language=file_path.suffix[1:],
                        class_name=class_name,
                        metadata=metadata,
                    )

                    # Initialize tokenizer for this model if Qwen
                    if self.tokenizer is None:
                        try:
                            self.tokenizer = AutoTokenizer.from_pretrained(
                                self.embedding_model_name
                            )
                        except Exception as e:
                            logging.warning(f"Failed to load Qwen tokenizer: {e}")
                            self.tokenizer = None

                    self.snippets.append(snippet)
                    snippet_count += 1

                except Exception as e:
                    logging.warning(f"Failed to load snippet from {file_path}: {e}")

        logging.info(f"Loaded {snippet_count} code snippets from {self.code_directory}")
        return snippet_count

    def load_embeddings_from_cache(self) -> bool:
        """
        Load pre-computed embeddings from cache.

        Returns:
            True if cache loaded successfully, False otherwise
        """
        if (
            not self.cache_embeddings
            or not self.cache_file
            or not self.cache_file.exists()
        ):
            return False

        try:
            with self.cache_file.open("rb") as f:
                cache_data = pickle.load(f)

            if "embeddings" in cache_data and "snippets" in cache_data:
                self.embeddings = cache_data["embeddings"]
                self.snippets = cache_data["snippets"]
                logging.info(
                    f"Loaded embeddings for {len(self.snippets)} snippets from cache"
                )
                return True
            else:
                logging.warning("Invalid cache format")
                return False

        except Exception as e:
            logging.warning(f"Failed to load embeddings from cache: {e}")
            return False

    def save_embeddings_to_cache(self):
        """Save embeddings and snippets to cache file."""
        if not self.cache_embeddings or not self.cache_file:
            return

        try:
            cache_data = {"embeddings": self.embeddings, "snippets": self.snippets}

            with self.cache_file.open("wb") as f:
                pickle.dump(cache_data, f)

            logging.info(f"Saved embeddings for {len(self.snippets)} snippets to cache")

        except Exception as e:
            logging.warning(f"Failed to save embeddings to cache: {e}")

    def load_embedding_model(self, model_name: str = None):
        """
        Load the embedding model (supports both sentence-transformers and Qwen models).
        Automatically downloads from Hugging Face if not installed locally.

        Args:
            model_name: Name or path of the embedding model (uses config value if None)
        """
        if model_name is None:
            model_name = self.embedding_model_name

        logging.info(f"Loading embedding model: {model_name}")

        if AutoTokenizer is not None:
            if self._model_loaded:
                logging.debug("Model already loaded, skipping")
                return

            try:
                # Try to load locally first
                self.tokenizer = AutoTokenizer.from_pretrained(model_name)
                self.embedding_model = AutoModel.from_pretrained(model_name)
                self._model_loaded = True
                logging.info("Embedding model loaded successfully (local)")

            except OSError as e:
                # Model not found locally, try to download from Hugging Face
                if "does not exist" in str(e) or "cannot find" in str(e):
                    logging.warning(f"Model not found locally: {model_name}")
                    logging.info(
                        f"Attempting to download from Hugging Face: {model_name}"
                    )

                    try:
                        from huggingface_hub import snapshot_download, login

                        # Login to Hugging Face (if not already logged in)
                        try:
                            login(token=os.getenv("HF_TOKEN", None))
                        except:
                            logging.info(
                                "Skipping Hugging Face login (token not provided or already logged in)"
                            )

                        # Download model from Hugging Face
                        model_path = snapshot_download(
                            repo_id=model_name, repo_type="model", resume_download=True
                        )

                        logging.info(f"Model downloaded successfully to: {model_path}")
                        self.tokenizer = AutoTokenizer.from_pretrained(model_path)
                        self.embedding_model = AutoModel.from_pretrained(model_path)
                        self._model_loaded = True
                        logging.info("Qwen model loaded successfully (downloaded)")

                    except ImportError:
                        logging.error("huggingface_hub not installed. Install with:")
                        logging.error("  pip install huggingface_hub")
                        raise ImportError(
                            f"Model '{model_name}' not found locally and huggingface_hub not installed. "
                            f"Install it with: pip install huggingface_hub"
                        )
                    except Exception as download_error:
                        logging.error(
                            f"Failed to download model from Hugging Face: {download_error}"
                        )
                        logging.error(
                            f"Available Qwen models at: https://huggingface.co/models?search=Qwen"
                        )
                        logging.error(
                            "Set HF_TOKEN environment variable to authenticate:"
                        )
                        logging.error("  export HF_TOKEN=your_token_here")
                        raise

                else:
                    logging.error(f"Failed to load Qwen model: {e}")
                    raise
            except Exception as e:
                logging.error(f"Failed to load Qwen model: {e}")
                raise

        else:
            raise ImportError(
                f"No suitable embedding model loader available. " f"Model: {model_name}"
            )

    def embed_snippets(self):
        """Generate embeddings for all stored snippets."""
        if not self._model_loaded:
            raise RuntimeError(
                "Embedding model not loaded. Call load_embedding_model() first."
            )

        if not self.snippets:
            logging.warning("No snippets to embed")
            return

        try:
            logging.info(f"Embedding {len(self.snippets)} snippets...")
            # Qwen approach - handle multi-chunk snippets
            all_embeddings = []

            for snippet in self.snippets:
                # For multi-chunk snippets, embed all chunks
                chunks_to_embed = (
                    snippet.chunks if snippet.chunks else [snippet.content]
                )

                for chunk in chunks_to_embed:
                    if hasattr(self, "tokenizer") and self.tokenizer is not None:
                        # Qwen token-level embedding
                        inputs = self.tokenizer(
                            chunk,
                            return_tensors="pt",
                            padding=True,
                            truncation=True,
                            max_length=512,
                        )
                        outputs = self.embedding_model(**inputs)
                        # Mean pooling to get single embedding
                        embedding = (
                            outputs.last_hidden_state.mean(dim=1)
                            .detach()
                            .numpy()
                            .flatten()
                        )
                    else:
                        raise RuntimeError("No tokenizer available for Qwen model")

                    all_embeddings.append(embedding)

                self.embeddings = np.array(all_embeddings)

            logging.info(
                f"Successfully embedded {len(self.snippets)} snippets (total {len(self.embeddings)} embeddings)"
            )

            # Save to cache if enabled
            if self.cache_embeddings:
                self.save_embeddings_to_cache()

        except Exception as e:
            logging.error(f"Failed to embed snippets: {e}")
            raise

    def embed_query(self, query: str) -> Optional[list]:
        """
        Generate an embedding for a query string.

        Args:
            query: Query string to embed

        Returns:
            Query embedding vector, or None if model not loaded
        """
        if not self._model_loaded:
            raise RuntimeError(
                "Embedding model not loaded. Call load_embedding_model() first."
            )

        try:
            return self.embedding_model.encode(query, convert_to_numpy=True)
        except Exception as e:
            logging.error(f"Failed to embed query: {e}")
            return None

    def find_similar_snippets(
        self,
        query: str,
        top_k: int = 5,
        threshold: float = 0.7,
        return_scores: bool = True,
    ) -> List[Tuple[CodeSnippet, float]]:
        """
        Find the most similar code snippets to a query.

        For multi-chunk snippets, uses the best matching chunk.

        Args:
            query: Query string for similarity search
            top_k: Number of top results to return
            threshold: Minimum similarity score (0-1)
            return_scores: Whether to return similarity scores

        Returns:
            List of (snippet, similarity_score) tuples
        """
        if not self._model_loaded:
            raise RuntimeError(
                "Embedding model not loaded. Call load_embedding_model() first."
            )

        if not self.snippets or self.embeddings is None:
            return []

        try:
            # Generate embedding for query
            query_embedding = self.embed_query(query)

            # Compute cosine similarity
            if (
                SentenceTransformer is not None
                and not self.embedding_model_name.startswith("Qwen")
            ):
                # Sentence-transformers approach
                cos_scores = util.cos_sim(query_embedding, self.embeddings)[0]
            else:
                # Qwen approach - compute cosine similarity with numpy
                cos_scores = np.dot(self.embeddings, query_embedding) / (
                    np.linalg.norm(self.embeddings, axis=1)
                    * np.linalg.norm(query_embedding)
                )

            # Get top-k results
            top_indices = np.argsort(cos_scores)[::-1][: min(top_k, len(self.snippets))]

            # Filter by threshold
            filtered_results = []
            for idx in top_indices:
                score = float(cos_scores[idx])
                if score >= threshold:
                    filtered_results.append((self.snippets[idx], score))

            return filtered_results

        except Exception as e:
            logging.error(f"Failed to find similar snippets: {e}")
            return []

    def initialize(self, model_name: str = None):
        """
        Initialize the snippet store: load snippets and embeddings.

        Args:
            model_name: Name or path of the embedding model
        """
        # Try to load from cache first
        if self.load_embeddings_from_cache():
            # Load embedding model even from cache for queries
            self.load_embedding_model(model_name)
            return

        # Load code snippets
        self.load_snippets()

        # Load and compute embeddings
        self.load_embedding_model(model_name)
        self.embed_snippets()


class SemanticSimilaritySearch:
    """
    Main class for semantic similarity-based code retrieval.
    """

    def __init__(self, config_path: Optional[str] = None):
        """
        Initialize the semantic similarity search.

        Args:
            config_path: Path to RAG configuration file
        """
        self.config = self._load_config(config_path)
        self.snippet_store = None

    def _load_config(self, config_path: Optional[str]) -> Dict[str, Any]:
        """
        Load RAG configuration from file.

        Args:
            config_path: Path to configuration file

        Returns:
            Configuration dictionary
        """
        if config_path is None or not os.path.exists(config_path):
            # Use default configuration with Path objects
            script_dir = Path(__file__).parent.resolve()
            default_config = {
                "embedding_model": "Qwen/Qwen3-Embedding-4B",
                "embedding_dimension": 2048,
                "max_snippets": 5,
                "similarity_threshold": 0.7,
                "max_snippet_length": 500,
                "max_chunk_size": 1024 * 4,  # 4KB chunks for AST-based chunking
                "chunk_overlap": 512,  # 512 char overlap
                "search_fields": ["test_class", "focal_class", "oracles"],
                "code_directory": script_dir.parent.parent / "input" / "github-repos",
                "cache_embeddings": True,
                "cache_file": script_dir / "resources" / "embeddings_cache.pkl",
            }
            return default_config

        try:
            with open(config_path, "r") as f:
                config = json.load(f)

            # Convert string paths to Path objects
            if "code_directory" in config and config["code_directory"]:
                config["code_directory"] = Path(config["code_directory"]).resolve()
            if "cache_file" in config and config["cache_file"]:
                config["cache_file"] = Path(config["cache_file"]).resolve()

            # Add default values if not present
            if "embedding_dimension" not in config:
                config["embedding_dimension"] = 2048  # Qwen uses 2048-dim embeddings
            if "max_chunk_size" not in config:
                config["max_chunk_size"] = 1024 * 4  # 4KB
            if "chunk_overlap" not in config:
                config["chunk_overlap"] = 512  # 512 char overlap

            return config
        except Exception as e:
            logging.warning(f"Failed to load config from {config_path}: {e}")
            script_dir = Path(__file__).parent.resolve()
            return {
                "embedding_model": "Qwen/Qwen3-Embedding-4B",
                "embedding_dimension": 2048,
                "max_snippets": 5,
                "similarity_threshold": 0.7,
                "max_snippet_length": 500,
                "max_chunk_size": 1024 * 4,
                "chunk_overlap": 512,
                "search_fields": ["test_class", "focal_class", "oracles"],
                "code_directory": script_dir.parent.parent / "input" / "github-repos",
                "cache_embeddings": True,
                "cache_file": script_dir / "resources" / "embeddings_cache.pkl",
            }

    def initialize(self):
        """Initialize the snippet store with code snippets and embeddings."""
        # Resolve code directory path relative to config file
        config_dir = os.path.dirname(
            os.path.abspath(self.config.get("config_file", "."))
        )
        code_dir = self.config.get("code_directory")

        if code_dir is None:
            raise ValueError(
                "Missing code_directory of the target project in the config file!"
            )

        # Resolve to absolute path
        if not os.path.isabs(code_dir):
            code_dir = os.path.join(config_dir, code_dir)

        self.snippet_store = CodeSnippetStore(
            code_directory=code_dir,
            max_snippet_length=self.config.get("max_snippet_length", 500),
            cache_embeddings=self.config.get("cache_embeddings", True),
            cache_file=str(self.config.get("cache_file", None)),
            embedding_model_name=self.config.get(
                "embedding_model", "Qwen/Qwen3-Embedding-4B"
            ),
            chunk_size=self.config.get("max_chunk_size", 1024 * 4),
            chunk_overlap=self.config.get("chunk_overlap", 512),
            embedding_dimension=self.config.get("embedding_dimension", 2048),
        )

        self.snippet_store.initialize()

    def retrieve_relevant_context(
        self,
        test_prefix: str,
        max_snippets: Optional[int] = None,
        threshold: Optional[float] = None,
    ) -> str:
        """
        Retrieve relevant code snippets for a given test prefix.

        Args:
            test_prefix: Test prefix string to search for similar snippets
            max_snippets: Maximum number of snippets to retrieve (overrides config)
            threshold: Minimum similarity score (overrides config)

        Returns:
            Formatted string containing retrieved context
        """
        if self.snippet_store is None:
            self.initialize()

        # Use config values or override with arguments
        max_snippets = max_snippets or self.config.get("max_snippets", 5)
        threshold = threshold or self.config.get("similarity_threshold", 0.7)

        # Find similar snippets
        results = self.snippet_store.find_similar_snippets(
            query=test_prefix, top_k=max_snippets, threshold=threshold
        )

        # Format results
        context_lines = ["// Similar code patterns from repository:"]
        for i, (snippet, score) in enumerate(results, 1):
            context_lines.append(f"```{snippet.language}")
            context_lines.append(snippet.content)
            context_lines.append("```")
            context_lines.append(f"// Similarity: {score:.3f}")

        if not results:
            context_lines.append("// No relevant code snippets found")

        return "\n".join(context_lines)


def retrieve_relevant_context(
    test_prefix: str,
    config_path: str = "scripts/prompt/rag/config.json",
    max_snippets: int = 5,
    threshold: float = 0.7,
) -> str:
    """
    Convenience function to retrieve relevant context for a test prefix.

    Args:
        test_prefix: Test prefix string to search for similar snippets
        config_path: Path to RAG configuration file
        max_snippets: Maximum number of snippets to retrieve
        threshold: Minimum similarity score

    Returns:
        Formatted string containing retrieved context
    """
    search = SemanticSimilaritySearch(config_path)
    search.initialize()
    return search.retrieve_relevant_context(
        test_prefix=test_prefix, max_snippets=max_snippets, threshold=threshold
    )
