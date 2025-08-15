import os
import json

if __name__ == '__main__':
    json_file_path = "/Users/davidemolinelli/Documents/phd/repositories/tracto/symbolic-module/output/dataset/206482/oracles-datapoints-206482-0.json"
    output_dir = "/Users/davidemolinelli/Documents/phd/repositories/tracto/symbolic-module/output/dataset/206482"

    with open(json_file_path, 'r') as json_file:
        json_list = json.load(json_file)

    with open(os.path.join(output_dir, os.path.basename(json_file_path).replace(".json", "_beautify.json")), 'w') as beautify_json_file:
        json.dump(json_list, beautify_json_file, indent=4)