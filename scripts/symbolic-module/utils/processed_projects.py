import sys, os, json, csv

def main():
    projects_path = sys.argv[1]
    dataset_path = sys.argv[2]
    dataset_ids = set()
    processed_ids = set()
    found = set()
    missing = set()
    inconsistent = set()

    # Collect dataset files to process
    if os.path.isdir(dataset_path):
        raw_dataset_files = []
        for root, dirs, files in os.walk(dataset_path):
            for file in files:
                if file.endswith('.json'):
                    project_id = file.split("oracles-datapoints-")[1].split("-")[0]
                    dataset_ids.add(project_id)
    else:
        if dataset_path.endswith('.json'):
            dataset_ids = {dataset_path.split("oracles-datapoints-")[1].split("-")[0]}

    # Collect projects files to process
    if os.path.isdir(projects_path):
        raw_dataset_files = []
        for root, dirs, files in os.walk(projects_path):
            for file in files:
                if file.endswith('.csv'):
                    with open(os.path.join(root, file), 'r', newline='') as csv_file:
                        reader = csv.reader(csv_file)
                        for row in reader:
                            processed_ids.add(row[0])
    else:
        if projects_path.endswith('.csv'):
            with open(os.path.join(projects_path), 'r', newline='') as csv_file:
                reader = csv.reader(csv_file)
                for row in reader:
                    processed_ids.add(row[0])

    for project_id in processed_ids:
        if project_id in dataset_ids:
            found.add(project_id)
        else:
            missing.add(project_id)
    for project_id in dataset_ids:
        if project_id not in processed_ids:
            inconsistent.add(project_id)

    print(f"Found ({len(found)}) - IDs: {', '.join(found)}")
    print(f"Missing ({len(missing)}) - IDs: {', '.join(missing)}")
    print(f"Inconsistent ({len(inconsistent)}) - IDs: {', '.join(inconsistent)}")


if __name__ == "__main__":
    main()