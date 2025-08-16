import json
import sys
import os
import csv

def json_to_csv(json_data, csv_file):
  """
  Converts a m2t JSON list to a m2t CSV file to be parsed by the generateDataset.sh script

  Args:
    json_data: A list of dictionaries representing the repos data.
    csv_file_path: The path to the output CSV file.
  """
  # Open the CSV file for writing
  with open(csv_file, 'w', newline='') as csvfile:
    writer = csv.writer(csvfile)
    # Iterate through each dictionary in the JSON list
    for repo in json_data:
      # Extract values as a list
      row_data = [repo["id"], repo["html_url"], repo["last_commit"]["sha"]]
      writer.writerow(row_data)

if __name__ == '__main__':
    # Read absolute path to JSON file from command line arguments
    json_file_path = sys.argv[1]
    # Generate CSV file path by replacing the file extension
    csv_file_path = json_file_path.replace(".json", ".csv")
    # Load JSON data from file
    with open(json_file_path, 'r') as json_file:
        json_data = json.load(json_file)
    # Convert JSON data to CSV file
    json_to_csv(json_data, csv_file_path)
    # Script completed
    print(f"JSON data converted to CSV file: {csv_file_path}")