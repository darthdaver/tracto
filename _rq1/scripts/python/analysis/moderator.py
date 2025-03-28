import csv
import json
import re
import os
import sys
import ollama
from sympy.solvers.ode.hypergeometric import equivalence
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


def llm_assertion_moderator(llm, num_ctx, query):
    """
    Use the LLM as moderator to determine if the target and the prediction assertions are equivalent.
    Args:
        llm: the identifier of the LLM model to use as moderator.
        num_ctx: the context length of the model
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
                "num_predict": 100
            }
        )
        # Use regex to extract whatever is between <keep> and </keep>
        match = re.search(r'<keep>(.*?)</keep>', response['response'].strip(), re.DOTALL)
        if match:
            out = match.group(1).strip()
            logger.log(f"Response from {llm}: {out}")
        else:
            logger.log(f"Response from {llm}: Unable to extract content")
        if out in ["EQUIVALENT", "NOT EQUIVALENT"]:
            return out
        else:
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
    # Read the model list from the file where they are listed
    llms = []
    with open(moderator_args.model_list_path, 'r') as model_list_file:
        csv_reader = csv.reader(model_list_file)
        for row in csv_reader:
            ollama_model_name = row[0]
            num_ctx = int(row[2])
            llms.append((ollama_model_name, num_ctx))
    # Get query text template
    with open(moderator_args.query_path, 'r') as query_file:
        query_template = query_file.read()

    # Process each model folder within the input directory
    for model_preds_dir in os.listdir(moderator_args.input_path):
        # Extract the name of the model and the query template id
        model_id = model_preds_dir.split('-')[0]
        query_id = int(model_preds_dir.split('-')[1])
        assert model_id in [ l[0] for l in llms ]
        logger.log(f"Processing model {model_id} with query template {query_id}")
        # Initialize the list of the results of the moderation task for the current model-query couple
        moderators_stats = []
        # Process each file of predictions within the model folder
        for preds_file_name in os.listdir(model_preds_dir):
            if preds_file_name.endswith(".csv"):
                logger.log(f"Processing file {preds_file_name}")
                preds_file_path = os.path.join(model_preds_dir, preds_file_name)
                with open(preds_file_path, 'r', newline='', encoding='utf-8') as preds_file:
                    reader = csv.DictReader(preds_file)
                    # Process each prediction from the file
                    for row in reader:
                        # Generate the query for the moderators, from the query template
                        query = preprocess_query(query_template, target, prediction)
                        target = row['target'].rstrip(';')
                        raw_prediction = row['prediction']
                        # Use regex to extract the assertion between <keep> and </keep>
                        match = re.search(r'<keep>(.*?)</keep>', raw_prediction, re.DOTALL)
                        if match:
                            pred_stats['parsing'] = True
                            prediction = match.group(1).strip(';')
                        else:
                            prediction = raw_prediction.strip(';')
                        logger.log(f"Analyzing equivalence between {target} and {prediction}")
                        pred_stats = {
                            "file_path": preds_file_path.replace(moderator_args.input_path, ''),
                            "prompt": query,
                            "target": row['target'],
                            "raw_prediction": raw_prediction,
                            "prediction": prediction,
                            "parsing": False,
                            "match": True if prediction == target else False
                        }
                        # Run the moderators
                        for llm, num_ctx in llms:
                            print(f"\n{model_preds_dir}: Comparing {target} with {prediction}")
                            result = llm_assertion_moderator(query, llm, num_ctx)
                            pred_stats[llm] = result
                        log_equivalence_threshold(pred_stats, [ l[0] for l in llms ])
                        moderators_stats.append(pred_stats)
        # Save the results of the moderation task
        output_file = os.path.join(moderator_args.output_path, f"{model_preds_dir}.json")
        logger.log(f"Processing complete. Results saved to {output_file}.")
        with open(output_file, 'w', newline='', encoding='utf-8') as output:
            json.dump(moderators_stats, output, indent=4)
