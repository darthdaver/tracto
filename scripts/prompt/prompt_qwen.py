import os
import sys
import json
import csv
import logging
import copy
from transformers import HfArgumentParser
sys.path.append(os.path.join(os.path.dirname(__file__), ".."))
from src.EwashArgsParser import EwashArgsParser
from src.EWashQwen import EWash
from src.Logger import Logger


# Setup the logger
logger = Logger("main", "ewash")

LITERALS = [
    "DECIMAL_LITERAL",
    "HEX_LITERAL",
    "OCT_LITERAL",
    "BINARY_LITERAL",
    "FLOAT_LITERAL",
    "HEX_FLOAT_LITERAL",
    "BOOL_LITERAL",
    "CHAR_LITERAL",
    "STRING_LITERAL",
    "LAMBDA_PARAM"
]


def update_stats(stats, tc_id, tp_id, tgt, exceeded, num_tokens):
    """
    Update the statistics dictionary with the information about the target oracle, the flag indicating if the input length
    limit was exceeded, and the number of tokens in the input string.
    :param stats: the dictionary containing the statistics about the datapoints processed
    :param tp_id: the identifier of the test prefix
    :param tgt: the target oracle to generate
    :param exceeded: the flag indicating if the input length limit was exceeded
    :param num_tokens: the number of tokens in the input string
    """
    if not tc_id in stats:
        stats['stats'][tc_id] = {}
    if not tp_id in stats:
        stats['stats'][tc_id][tp_id] = []
    stats['stats'][tc_id][tp_id].append({"tgt": tgt, "exceeded": exceeded, "num_tokens": num_tokens})

def stats_error(stats, tc, error):
    """
    Update the statistics dictionary with the error information for the given test prefix.
    :param stats: the dictionary containing the statistics about the datapoints processed
    :param tc: the test class (or 'null' if not available)
    :param error: the error message
    """
    if not tc in stats['errors']:
        stats[tc] = []
    stats[tc].append({"error": error})

if __name__ == '__main__':
    """
    The program process the raw JSON dataset and generates the input for the model using the E-WASH approach.
    The prompt is customizable and can be configured to include information about the invoked methods, the test class,
    and the focal class. The E-WASH approach integrates the information progressively, only if the whole information fits
    in the input length limit of the model. The program generates the input for the model in the CSV format, where each
    row contains the model input of a datapoint and the corresponding target oracle.
    """
    # Input and output paths
    # Parse arguments
    parser = HfArgumentParser(EwashArgsParser)
    args = parser.parse_args_into_dataclasses()[0]

    input_path = args.input
    output_path = args.output
    config_path = args.config
    intros_path = args.intros
    # Load the configuration file for the E-WASH approach
    with open(config_path, 'r') as config_file:
        ewash_config = json.load(config_file)
    # Load the dictionary of intro comments for each section of the input for the E-WASH approach
    with open(intros_path, 'r') as intros_file:
        intros_template = json.load(intros_file)
    # Collect files to process
    if os.path.isdir(input_path):
        raw_dataset_files = []
        for root, dirs, files in os.walk(input_path):
            for file in files:
                if file.endswith('.json'):
                    raw_dataset_files.append(os.path.join(root, file))
    else:
        raw_dataset_files = [input_path]

    datapoints_counter = 0
    # Process each JSON file in the original json dataset
    for filename in raw_dataset_files:
        try:
            logger.log(f'Processing file {filename}', logging.INFO)
            json_data_list = []
            file_path = os.path.join(input_path, filename)
            # Read the JSON file and load the data
            with open(file_path, 'r') as json_file:
                test_classes_datapoints = json.load(json_file)
            output_prompt_path = os.path.join(output_path, 'prompt', os.path.basename(filename).replace(".json", ".csv"))
            output_stats_path = os.path.join(output_path, 'stats', os.path.basename(filename).replace(".json", "_stats.json"))
            output_info_path = os.path.join(output_path, 'info', os.path.basename(filename).replace(".json", "_info.json"))
            # Create the output prompt directory if it does not exist
            if not os.path.exists(os.path.dirname(output_prompt_path)):
                os.makedirs(os.path.dirname(output_prompt_path))
            # Create the output stats directory if it does not exist
            if not os.path.exists(os.path.dirname(output_stats_path)):
                os.makedirs(os.path.dirname(output_stats_path))
            # Create the output info directory if it does not exist
            if not os.path.exists(os.path.dirname(output_info_path)):
                os.makedirs(os.path.dirname(output_info_path))
            # Initialize statistics
            stats = {'stats': {}, 'errors': {}}
            # Open the CSV file for writing the dataset
            with open(output_prompt_path, 'w', newline='') as csv_file:
                writer = csv.writer(csv_file)
                # Write the header
                writer.writerow(["id", "src", "tgt", "unmask_tokens"] if ewash_config['type'] == "tokens" else ["id", "src", "tgt"])
                # Process each datapoint in the JSON list
                for tcs_d in test_classes_datapoints:
                    # Initialize the counter of the tokens in the input to the model
                    tokens_counter = 0
                    # Get the information about the test class and the focal class
                    tc = tcs_d['testClass']
                    fc = tcs_d['focalClass']
                    # Get the JUnit version used in the test class to generate the target oracle
                    ju_v = tcs_d['junitVersion']
                    # Normalize sections to conform to the expected format
                    if tc is None or fc is None:
                        logger.log(f"Test class or focal class is None.", logging.ERROR)
                        stats_error(stats, tc if not tc is None else 'null', "Test class or focal class is None")
                        continue
                    tc = EWash.normalize_section(tc)
                    fc = EWash.normalize_section(fc)
                    # Iterate over the datapoints in the test class and create the corresponding input for the model
                    for i_d,d in enumerate(tcs_d['datapoints']):
                        datapoints_counter += 1
                        test_prefix_signature = d['testPrefix']['signature']
                        # Get the test prefix of the datapoint and the target oracle to generate
                        tp = EWash.normalize_section(d['testPrefix'])
                        datapoint_id = f"{tc['identifier']}-{tp['identifier']}-{datapoints_counter}"
                        oracle = d['target']
                        intros = copy.deepcopy(intros_template)
                        logger.log(f"Processing datapoint {tp['signature']}, oracle: {oracle}", logging.INFO)

                        if ewash_config['type'] == "oracles":
                            # Get target (the whole oracle)
                            tgt = oracle
                            try:
                                if i_d == 0:
                                    # Initialize the E-WASH dictionary
                                    ewash = EWash(tp, tc, fc, ju_v, intros, ewash_config)
                                    # Generate the input string for the model using the E-WASH approach
                                    src, exceeded, num_tokens = ewash.run(['test_prefix', 'test_class', 'focal_class'])
                                else:
                                    ewash.update(intros, tp, ju_v, update_section=True)
                                    src, exceeded, num_tokens = ewash.run(['test_prefix'])
                                # Write the input and the target oracle to the CSV file
                                writer.writerow([datapoint_id, src, oracle])
                                json_data_list.append({
                                    "id": datapoint_id,
                                    "src": src,
                                    "tgt": oracle,
                                    "test_class_path": tcs_d['testClass']['filePath'],
                                    "signature": test_prefix_signature,
                                    "tp_body": tp['body'],
                                    "exceeded": exceeded,
                                    "num_tokens": num_tokens
                                })
                                # Update stats
                                update_stats(stats, tc['identifier'], tp['signature'], tgt, exceeded, num_tokens)
                            except ValueError as e:
                                # Update stats
                                update_stats(stats, tc['identifier'], tp['signature'], tgt, True, -1)
                        elif ewash_config['type'] == "tokens":
                            for i_s, s in enumerate(d['tokenSteps']):
                                if "target" in s:
                                    # Get partial oracle already generated
                                    partial_oracle = s['partialSnippet']
                                    # Get target (next token)
                                    if ewash_config['scope'] == "training":
                                        tgt = s['target']
                                        if len(tgt.split(":")) < 2:
                                            logger.log(f"Target token {tgt} is not in the expected format.", logging.ERROR)
                                            stats_error(stats, tc['identifier'], f"Target token {tgt} is not in the expected format")
                                            continue
                                        logger.log(f"Processing step. Partial oracle: {partial_oracle}, next token: {tgt}", logging.INFO)
                                        print(f"Processing step. Partial oracle: {partial_oracle}, next token: {tgt}")
                                    else:
                                        tgt = ""
                                        print(f"Processing step. Partial oracle: {partial_oracle}")
                                    tokens = EWash.normalize_section(s)
                                    try:
                                        if i_d == 0 and i_s == 0:
                                            # Initialize the E-WASH dictionary
                                            ewash = EWash(tp, tc, fc, ju_v, intros, ewash_config, partial_oracle, tokens)
                                            # Generate the input string for the model using the E-WASH approach
                                            src, exceeded, num_tokens = ewash.run(
                                                ['test_prefix', 'test_class', 'focal_class'])
                                        else:
                                            try:
                                                if i_s == 0:
                                                    ewash.update(intros, tp, ju_v, partial_oracle, tokens, True)
                                                    src, exceeded, num_tokens = ewash.run(['test_prefix'], True)
                                                else:
                                                    ewash.update(intros, tp, ju_v, partial_oracle, tokens)
                                                    src, exceeded, num_tokens = ewash.run([], True)
                                            except ValueError as e:
                                                if ewash_config['scope'] == 'training':
                                                    logger.log(f"Exceeded input limit when updating the input string with the next token: {tgt}. It is necessary to reinitialize the E-WASH dictionary.",logging.ERROR)
                                                else:
                                                    logger.log(f"Exceeded input limit when updating the input string with the next token step. It is necessary to reinitialize the E-WASH dictionary.", logging.ERROR)
                                                # Re-initialize the E-WASH dictionary
                                                intros = copy.deepcopy(intros_template)
                                                ewash = EWash(tp, tc, fc, ju_v, intros, ewash_config, partial_oracle, tokens)
                                                # Generate the input string for the model using the E-WASH approach
                                                src, exceeded, num_tokens = ewash.run(['test_prefix', 'test_class', 'focal_class'])

                                        src = src + ewash_config['infix_tag']

                                        unmask_tokens = []
                                        for t_type in ewash_config['tokens']:
                                            if len(tokens[t_type]) > 0:
                                                unmask_tokens.extend(tokens[t_type])

                                        if ewash_config['scope'] == "training":
                                            if not tgt.split(':')[0] in LITERALS:
                                                tgt = f"{{\"choice\": \"{":".join(tgt.split(':')[1:])}\"}}"
                                                writer.writerow([datapoint_id, src, tgt, unmask_tokens])
                                                json_data_list.append({
                                                    "id": datapoint_id,
                                                    "src": src,
                                                    "tgt": tgt,
                                                    "test_class_path": tcs_d['testClass']['filePath'],
                                                    "signature": test_prefix_signature,
                                                    "tp_body": tp['body'],
                                                    "exceeded": exceeded,
                                                    "num_tokens": num_tokens
                                                })
                                                # Update stats
                                                update_stats(stats, tc['identifier'], tp['signature'], tgt, exceeded, num_tokens)
                                            else:
                                                tgt_1 = f"{{\"choice\": \"{tgt.split(':')[0]}\"}}"
                                                writer.writerow([datapoint_id, src, tgt_1, unmask_tokens])
                                                json_data_list.append({
                                                    "id": datapoint_id,
                                                    "src": src,
                                                    "tgt": tgt_1,
                                                    "test_class_path": tcs_d['testClass']['filePath'],
                                                    "signature": test_prefix_signature,
                                                    "tp_body": tp['body'],
                                                    "exceeded": exceeded,
                                                    "num_tokens": num_tokens,
                                                    "scope": "training",
                                                    "phase": "choice"
                                                })
                                                # Update stats
                                                update_stats(stats, tc['identifier'], tp['signature'], tgt, exceeded,num_tokens)
                                                if not tgt.split(':')[0] == "LAMBDA_PARAM":
                                                    src = src.replace(intros['guideline']['1']['task'], intros['guideline']['2']['task'].replace('<LITERAL_TYPE>', tgt.split(':')[0]))
                                                else:
                                                    src = src.replace(intros['guideline']['1']['task'], "EMIT a Java lambda parameter name (i.e., the variable used to represent the input in the following lambda expression).")
                                                src = src.replace(intros['guideline']['1']['output'], intros['guideline']['2']['output'])
                                                src = src.replace("# ADDITIONAL CONTEXTUAL INFORMATION (CAN GUIDE THE CHOICE):\n", "# ADDITIONAL CONTEXTUAL INFORMATION (CAN HELP THE GENERATION):\n")

                                                # Remove list of next tokens from the prompt
                                                start_marker = "# NEXT TOKEN CANDIDATES"
                                                end_marker = intros['guideline']['2']['task'].replace('<LITERAL_TYPE>', tgt.split(':')[0])
                                                # Find the start and end indices
                                                start_index = src.find(start_marker)
                                                end_index = src.find(end_marker)
                                                # Check if both markers are found
                                                if start_index != -1 and end_index != -1:
                                                    # Create the new string without the unwanted substring
                                                    src = src[:start_index] + src[end_index:]
                                                tgt_2 = ":".join(tgt.split(':')[1:])
                                                writer.writerow([datapoint_id, src, tgt_2, []])
                                                json_data_list.append({
                                                    "id": datapoint_id,
                                                    "src": src,
                                                    "tgt": tgt_2,
                                                    "test_class_path": tcs_d['testClass']['filePath'],
                                                    "signature": test_prefix_signature,
                                                    "tp_body": tp['body'],
                                                    "exceeded": exceeded,
                                                    "num_tokens": num_tokens,
                                                    "scope": "training",
                                                    "phase": "emit"
                                                })
                                                # Update stats
                                                update_stats(stats, tc['identifier'], tp['signature'], tgt, exceeded, num_tokens)
                                        else:
                                            if args.phase == "choice":
                                                writer.writerow([datapoint_id, src, tgt, unmask_tokens])
                                                json_data_list.append({
                                                    "id": datapoint_id,
                                                    "src": src,
                                                    "tgt": tgt,
                                                    "test_class_path": tcs_d['testClass']['filePath'],
                                                    "signature": test_prefix_signature,
                                                    "tp_body": tp['body'],
                                                    "exceeded": exceeded,
                                                    "num_tokens": num_tokens
                                                })
                                                # Update stats
                                                update_stats(stats, tc['identifier'], tp['signature'], tgt, exceeded, num_tokens)
                                            else:
                                                if not args.literal == "LAMBDA_PARAM":
                                                    src = src.replace(intros['guideline']['1']['task'], intros['guideline']['2']['task'].replace('<LITERAL_TYPE>', args.literal))
                                                else:
                                                    src = src.replace(intros['guideline']['1']['task'], "EMIT a Java lambda parameter name (i.e., the variable used to represent the input in the following lambda expression).")
                                                src = src.replace(intros['guideline']['1']['output'], intros['guideline']['2']['output'])
                                                src = src.replace("# ADDITIONAL CONTEXTUAL INFORMATION (CAN GUIDE THE CHOICE):\n", "# ADDITIONAL CONTEXTUAL INFORMATION (CAN HELP THE GENERATION):\n")
                                                # Remove list of next tokens from the prompt
                                                start_marker = "# NEXT TOKEN CANDIDATES"
                                                end_marker = intros['guideline']['2']['task'].replace('<LITERAL_TYPE>', args.literal)
                                                # Find the start and end indices
                                                start_index = src.find(start_marker)
                                                end_index = src.find(end_marker)
                                                # Check if both markers are found
                                                if start_index != -1 and end_index != -1:
                                                    # Create the new string without the unwanted substring
                                                    src = src[:start_index] + src[end_index:]
                                                writer.writerow([datapoint_id, src, tgt, []])
                                                json_data_list.append({
                                                    "id": datapoint_id,
                                                    "src": src,
                                                    "tgt": tgt,
                                                    "test_class_path": tcs_d['testClass']['filePath'],
                                                    "signature": test_prefix_signature,
                                                    "tp_body": tp['body'],
                                                    "exceeded": exceeded,
                                                    "num_tokens": num_tokens,
                                                    "scope": "training",
                                                    "phase": "emit"
                                                })
                                                # Update stats
                                                update_stats(stats, tc['identifier'], tp['signature'], tgt, exceeded, num_tokens)
                                    except ValueError as e:
                                        # Update stats
                                        update_stats(stats, tc['identifier'], tp['signature'], tgt, True, -1)
        except Exception as e:
            logger.log(f"Unexpected error: {e}", logging.ERROR)
            logger.log(
                f"Unexpected error while processing file: {filename} ({file_path}).",
                logging.ERROR
            )
            # Update stats
            update_stats(stats, tc['identifier'], tp['signature'], tgt, True, -1)
        # Save statistics
        with open(output_stats_path, 'w') as stats_file:
            json.dump(stats, stats_file)
        # Save the dataset info
        with open(output_info_path, 'w') as dp_info_file:
            json.dump(json_data_list, dp_info_file)
    logger.log(f"Processing completed.", logging.INFO)