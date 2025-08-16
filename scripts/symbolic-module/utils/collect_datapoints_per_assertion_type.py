import os
import sys
import json

if __name__ == "__main__":
    input_folder = sys.argv[1]
    assertion_type = sys.argv[2]
    assertions_folder = os.path.join(input_folder, "assertions_projects_per_type")
    raw_dataset_folder = os.path.join(input_folder, "raw-oracles-dataset")
    output_folder = os.path.join(input_folder, "assertions_datapoints_per_type")
    if not os.path.exists(output_folder):
        os.makedirs(output_folder)

    assertion_type_file_path = os.path.join(assertions_folder, assertion_type + ".json")

    assertion_type_data = []

    with open(assertion_type_file_path, 'r') as f:
        projects_list = json.load(f)
        for project_id in projects_list:
            project_id_assertion_data = {
                "project_id": project_id,
                "assertion_type": assertion_type,
                "datapoints": []
            }
            raw_dataset_folder_path = os.path.join(raw_dataset_folder, project_id)
            for root, dirs, files in os.walk(raw_dataset_folder_path):
                for file in files:
                    if file.endswith(".json"):
                        file_path = os.path.join(root, file)
                        with open(file_path, 'r') as f:
                            data = json.load(f)
                            for o in data:
                                for datapoint in o["datapoints"]:
                                    if assertion_type + "(" in datapoint["target"]:
                                        project_id_assertion_data["datapoints"].append({
                                            "testClass": o["testClass"]["filePath"],
                                            "testPrefix": datapoint["testPrefix"],
                                            "target": datapoint["target"]
                                        })
            assertion_type_data.append(project_id_assertion_data)

    with open(os.path.join(output_folder, assertion_type + ".json"), 'w') as f:
        json.dump(assertion_type_data, f, indent=4)