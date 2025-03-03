import json
import sys
import os
import csv

def json_to_csv(json_data, csv_file):
  """
  Converts a JSON list to a CSV file.

  Args:
    json_data: A list of dictionaries representing the JSON data.
    csv_file_path: The path to the output CSV file.
  """
  # Open the CSV file for writing
  with open(csv_file, 'w', newline='') as csvfile:
    writer = csv.writer(csvfile)
    # Get all keys from the first dictionary (assuming consistent structure)
    headers = list(json_data[0].keys())
    # Write headers to the CSV file
    writer.writerow(headers)
    # Iterate through each dictionary in the JSON list
    for item in json_data:
      # Extract values as a list
      row_data = [str(value) for value in item.values()]
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