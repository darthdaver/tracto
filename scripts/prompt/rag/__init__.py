"""
RAG (Retrieval-Augmented Generation) module for dynamic context retrieval.

This module provides functionality to retrieve semantically similar code snippets
from a repository and integrate them into prompt generation for enhanced
context awareness.
"""

from .context_retriever import CodeSnippetStore, SemanticSimilaritySearch, retrieve_relevant_context

__all__ = [
    'CodeSnippetStore',
    'SemanticSimilaritySearch',
    'retrieve_relevant_context',
]
