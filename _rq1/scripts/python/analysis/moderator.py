import csv
import json
import re
import os
import sys
import ollama
from sympy.solvers.ode.hypergeometric import equivalence
from torch.distributed.pipeline.sync.checkpoint import checkpoint
from transformers import HfArgumentParser
sys.path.append(os.path.join(os.path.dirname(__file__), "..", "..", "..", "..", "neural-module"))
from src.parsers.OllamaModeratorArgs import OllamaModeratorArgs
from src.utils.Logger import Logger

# Setup the logger
logger = Logger("main", "moderator")


def preprocess_query(query_template, target, prediction):
    """
    Preprocess a couple of target-prediction assertions that the moderator models have to evaluate.
    Args:
        query_template (`str`):
            The query template to use as prompt to the moderator models.
        target (`str`):
            The target assertion.
        prediction (`str`):
            The predicted assertion.
    """
    query = query_template.replace("<TARGET>", target)
    query = query.replace("<PREDICTION>", prediction)
    return query


def llm_assertion_moderator(llm, num_ctx, llm_type, query):
    """
    Use the LLM as moderator to determine if the target and the prediction assertions are equivalent.
    Args:
        llm: the identifier of the LLM model to use as moderator.
        num_ctx: the context length of the model
        llm_type: the type of the LLM model to use as moderator (`base` or `reasoning`).
        query: the query to use as prompt to the moderator model.

    Returns:
        The result of the equivalence evaluation (<EQUIVALENT>, <NOT_EQUIVALENT>, <UNKNOWN>, <ERROR>).
    """
    # Predict the output with ollama
    try:
        response = ollama.generate(
            model=llm,
            prompt=query,
            options={
                "num_ctx": int(num_ctx),
                "seed": 42,
                "num_predict": 500 if llm_type == "base" else 4096,
            }
        )
        # Use regex to extract whatever is between <keep> and </keep>
        match = re.search(r'<keep>(.*?)</keep>', response['response'].strip(), re.DOTALL)
        
        if llm_type == "reasoning":
            response['response'] = response['response'].split("</think>")[-1]
        
        if match:
            out = match.group(1).strip()
            logger.log(f"Response from {llm}: {out}")
            if out in ["EQUIVALENT", "NOT EQUIVALENT"]:
                return out
            else:
                logger.log(f"Response from {llm}: Regex extracted, but response different from ['EQUIVALENT', 'NOT EQUIVALENT']")
                return "UNKNOWN"
        else:
            match = re.search(r'\bassert\w+\((.*?)\);', response['response'].strip())
            if match:
                out = match.group(1).strip()
                logger.log(f"Response from {llm}: {out}")
                if out in ["EQUIVALENT", "NOT EQUIVALENT"]:
                    return out
                else:
                    logger.log(f"Response from {llm}: Regex extracted, but response different from ['EQUIVALENT', 'NOT EQUIVALENT']")
                    return "UNKNOWN"
            else:
                if response['response'].strip() in ["EQUIVALENT", "NOT EQUIVALENT"]:
                    return response['response'].strip()
                logger.log(f"Response from {llm}: Unable to extract content")
                return "UNKNOWN"
    except Exception as e:
        logger.log(f"Error while processing the input:\n{query}")
        logger.log(f"Error: {e}")
        return "ERROR"

def log_equivalence_threshold(pred_stats, llms):
    """
    Log the results of the equivalence evaluation of the assertions at different thresholds.
    Args:
        pred_stats: the dictionary containing the results of the moderation task for a given equivalence.
        llms: the list of the LLM models used as moderators.
    """
    equivalence_counter = 0
    for llm in llms:
        if pred_stats[llm] == "EQUIVALENT":
            equivalence_counter += 1
    logger.log(f"Equivalence threshold: {equivalence_counter}/{len(llms)}")
    return equivalence_counter


if __name__ == "__main__":
    """
    The script analyzes the output of the LLMs and uses all the models as moderators to determine if the ground truth and 
    the predicted assertions are equivalent.
    To individuate the best prompt to the model, we designed 4 different preliminary templates, integrating the test prefix
    and different combination of contextual information (signature, javadoc, body) about the methods invoked within the
    test prefix. We asked a list of vanilla LLMs (pretrained with text and/or code) to predict the assertions that should
    follow the test prefixes in the ground truth dataset.
    To measure the capability of the LLMs to predict equivalent assertions automatizing the manual evaluation, we ask 
    all the models to evaluate the equivalence of the predicted assertions with the ground truth ones. The models have to
    answer if the two assertions are equivalent or not, considering the semantics of the assertions.
    For each assertion predicted by the LLMs, we count the votes of the models that predict the equivalence of the assertions.
    The script saves the results in json format, for future analysis. The idea is that if the majority of the models predict 
    the equivalence of the assertions, we consider the assertions equivalent.
    """
    # Read the args passed to the script
    parser = HfArgumentParser(OllamaModeratorArgs)
    moderator_args = parser.parse_args_into_dataclasses()[0]
    input_path = moderator_args.input_path
    output_path = moderator_args.output_path
    checkpoint = 100
    # Read the model list from the file where they are listed
    llms = []
    with open(moderator_args.model_list_path, 'r') as model_list_file:
        csv_reader = csv.reader(model_list_file)
        for row in csv_reader:
            ollama_model_name = row[0]
            num_ctx = int(row[2])
            model_type = row[3]
            llms.append((ollama_model_name, num_ctx, model_type))
    # Get query text template
    with open(moderator_args.query_path, 'r') as query_file:
        query_template = query_file.read()
    # Process each model folder within the input directory
    for model_preds_dir in os.listdir(input_path):
        # Extract the name of the model and the query template id
        model_id = model_preds_dir.split(':')[0] + ':' + model_preds_dir.split(':')[1].replace("-" + model_preds_dir.split('-')[-1], "")
        query_id = int(model_preds_dir.split('-')[-1])
        assert model_id in [ l[0] for l in llms ]
        logger.log(f"Processing model {model_id} with query template {query_id}")
        # Initialize the list of the results of the moderation task for the current model-query couple
        moderators_stats = []
        # Process each file of predictions within the model folder
        for preds_file_name in os.listdir(os.path.join(input_path, model_preds_dir)):
            if preds_file_name.endswith(".csv"):
                logger.log(f"Processing file {preds_file_name}")
                preds_file_path = os.path.join(input_path, model_preds_dir, preds_file_name)
                with open(preds_file_path, 'r', newline='', encoding='utf-8') as preds_file:
                    reader = csv.reader(preds_file)
                    rows = list(reader)
                    # Process each prediction from the file
                    for i, row in enumerate(rows,1):
                        target = row[1].rstrip(';')
                        raw_prediction = row[2]
                        parsing = False
                        # Use regex to extract the assertion between <keep> and </keep>
                        match = re.search(r'<keep>(.*?)</keep>', raw_prediction, re.DOTALL)
                        if match:
                            parsing = True
                            prediction = match.group(1).strip(';')
                        else:
                            match = re.search(r'\bassert\w+\((.*?)\);', raw_prediction)
                            if match:
                                parsing = True
                                prediction = match.group(1).strip(';')
                            else:
                                prediction = raw_prediction.strip(';')
                        logger.log(f"Analyzing equivalence between {target} and {prediction}")
                        # Generate the query for the moderators, from the query template
                        query = preprocess_query(query_template, target, prediction)
                        pred_stats = {
                            "llm": model_id,
                            "file_path": preds_file_path.replace(input_path, ''),
                            "promt-prediction": row[0],
                            "prompt-moderator": query,
                            "target": target,
                            "raw_prediction": raw_prediction,
                            "prediction": prediction,
                            "parsing": parsing,
                            "match": True if target == prediction else False
                        }
                        # Run the moderators
                        for llm, num_ctx, llm_type in llms:
                            result = llm_assertion_moderator(llm, num_ctx, llm_type, query)
                            pred_stats[llm] = result
                        pred_stats['equivalence_counter'] = log_equivalence_threshold(pred_stats, [ l[0] for l in llms ])
                        moderators_stats.append(pred_stats)

                        if ((i % checkpoint) == 0) or (i == len(rows) - 1):
                            # Save the intermediate result of the moderation task
                            logger.log(f"Saving intermediate results to {output_path}")
                            if not os.path.exists(output_path):
                                os.makedirs(output_path)
                            output_file = os.path.join(output_path, f"{model_preds_dir}_{i/100}.json")
                            with open(output_file, 'w', newline='', encoding='utf-8') as output:
                                json.dump(moderators_stats, output, indent=4)
                            moderators_stats = []
