import sys
import json

input = sys.argv[1]

with open(input, 'r') as json_file:
    datapoints_lists = json.load(json_file)

flatten_list = [ item for sublist in datapoints_lists for item in sublist ]

with open(input.replace(".json", "_flatten.json"), 'w') as json_file:
    json.dump(flatten_list, json_file, indent=4)