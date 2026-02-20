import os, json, sys


def analyze_stats_assertions(file_path):
    try:
        with open(file_path, 'r') as f:
            data = json.load(f)
        # Manage the case in which the JSON is a single object instead of a list
        if isinstance(data, dict):
            data = [data]
        # Counters
        total_global_assertions = 0
        mismatched_objects = []
        print("--- Mismatch Analysis ---")
        for item in data:
            # Retrieve main values
            n_assertions = item.get('numberOfAssertions', 0)
            distribution = item.get('assertionsDistribution', {})
            # Increment global count
            total_global_assertions += n_assertions
            # Sum the values in the dictionary of assertions
            sum_distribution = sum(distribution.values())
            # Check consistency
            if sum_distribution != n_assertions:
                mismatched_objects.append({
                    "identifier": item.get('identifier', 'N/A'),
                    "signature": item.get('signature', 'N/A'),
                    "file_path": item.get('file_path', 'N/A'),
                    "expected": n_assertions,
                    "actual_sum": sum_distribution
                })
                print(f"Mismatch found in '{item.get('identifier')}':")
                print(f"  - Declared (numberOfAssertions): {n_assertions}")
                print(f"  - Distribution sum: {sum_distribution}\n")
        if not mismatched_objects:
            print("Awesome! All counters match perfectly.\n")
        print("--- Final Summary ---")
        print(f"Total sum of assertions retrieved in the project: {total_global_assertions}")
        return mismatched_objects
    except FileNotFoundError:
        print(f"Error: The file '{file_path}' cannot be found.")
        return []
    except json.JSONDecodeError:
        print(f"Error: The file '{file_path}' is not a valid JSON file.")
        return []


def analyze_stats_raw():
    pass


if __name__ == "__main__":
    stats_folder = sys.argv[1]
    raw_data_folder = stats_folder.split(os.path.sep)[-1].replace("statistics","raw")
    output_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "output")
    stats_path = os.path.join(output_path, stats_folder)
    for root, _, files in os.walk(stats_path):
        for file in files:
            abs_path = os.path.join(root, file)
            project_id = os.path.basename(os.path.dirname(abs_path))
            if abs_path.endswith(f'test-stats-{project_id}.json'):
                print(f"\nAnalyzing project: {project_id}")
                mismatch_objects = analyze_stats_assertions(project_id, abs_path)
