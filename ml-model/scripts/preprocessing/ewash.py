import os
import sys
import json
import csv
import logging
from src.preprocessing.EWash import EWash

# Setup the logger
logger = logging.getLogger("main")
logging.basicConfig(
    filename=os.path.join(os.path.dirname(os.path.abspath(__file__)),"ewash-oracles.log"),
    level=logging.INFO,
    format="[%(levelname)s] | %(name)s] | %(message)s"
)


def update_stats(stats, tp_id, tgt, exceeded, num_tokens):
    """
    Update the statistics dictionary with the information about the target oracle, the flag indicating if the input length
    limit was exceeded, and the number of tokens in the input string.
    :param stats: the dictionary containing the statistics about the datapoints processed
    :param tp_id: the identifier of the test prefix
    :param tgt: the target oracle to generate
    :param exceeded: the flag indicating if the input length limit was exceeded
    :param num_tokens: the number of tokens in the input string
    """
    if not tp_id in stats:
        stats[tp_id] = []
    stats[tp_id].append({"tgt": tgt, "exceeded": exceeded, "num_tokens": num_tokens})

if __name__ == '__main__':
    """
    The program process the raw JSON dataset and generates the input for the model using the E-WASH approach.
    The prompt is customizable and can be configured to include information about the invoked methods, the test class,
    and the focal class. The E-WASH approach integrates the information progressively, only if the whole information fits
    in the input length limit of the model. The program generates the input for the model in the CSV format, where each
    row contains the model input of a datapoint and the corresponding target oracle.
    """
    # Input and output paths
    input_path = sys.argv[1]
    output_path = sys.argv[2]
    # Get configuration file for customizable prompt composition
    ew_config_file_path = sys.argv[3] if len(sys.argv) > 3 else None
    # Load configuration file for customizable prompt composition
    if ew_config_file_path is not None:
        with open(ew_config_file_path, 'r') as config_file:
            ewash_config = json.load(config_file)
    # Initialize statistics
    stats = {}
    # Process each JSON file in the original json dataset
    for filename in os.listdir(input_path):
        if filename.endswith('.json'):
            logger.info(f'Processing file {filename}')
            file_path = os.path.join(input_path, filename)
            # Read the JSON file and load the data
            with open(file_path, 'r') as json_file:
                test_classes_datapoints = json.load(json_file)
            output_file_path = os.path.join(output_path, filename.replace(".json", ".csv"))
            # Open the CSV file for writing the dataset
            with open(output_file_path, 'w', newline='') as csv_file:
                writer = csv.writer(csv_file)
                # Write the header
                writer.writerow(["src", "tgt"])
                # Process each datapoint in the JSON list
                for tc_d in test_classes_datapoints:
                    # Initialize the counter of the tokens in the input to the model
                    tokens_counter = 0
                    # Get the information about the test class and the focal class
                    test_class = tc_d['testClass']
                    focal_class = tc_d['focalClass']
                    # Normalize sections to conform to the expected format
                    test_class = EWash.normalize_section(test_class)
                    focal_class = EWash.normalize_section(focal_class)
                    # Iterate over the datapoints in the test class and create the corresponding input for the model
                    for d in tc_d['datapoints']:
                        # Get the test prefix of the datapoint and the target oracle to generate
                        test_prefix = EWash.normalize_section(d['testPrefix'])
                        tgt = d['target']
                        logger.info(f"Processing datapoint {test_prefix['identifier']}, target: {tgt}")
                        # Initialize the E-WASH dictionary
                        ewash = EWash(test_prefix, test_class, focal_class, tgt, ewash_config)
                        # Generate the input string for the model using the E-WASH approach
                        src, exceeded, num_tokens = ewash.run()
                        # Write the input and the target oracle to the CSV file
                        writer.writerow([src, tgt])
                        # Update stats
                        update_stats(stats, test_prefix['identifier'], tgt, exceeded, num_tokens)
            # Save statistics
            with open(os.path.join(output_path, filename.replace(".json", "_stats.json")), 'w') as stats_file:
                json.dump(stats, stats_file)
    logger.info(f"Processing completed.")