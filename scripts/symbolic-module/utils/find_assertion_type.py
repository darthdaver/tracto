import os
import sys
import json

if __name__ == "__main__":
    input_file = sys.argv[1]

    projects_list = []

    output_folder = os.path.join(os.path.dirname(input_file), "assertions_projects_per_type")

    if not os.path.exists(output_folder):
        os.makedirs(output_folder)

    assetions_types = [
        "assertAll",
        "assertArrayEquals",
        "assertDoesNotThrow",
        "assertEquals",
        "assertFalse",
        "assertInstanceOf",
        "assertIterableEquals",
        "assertLinesMatch",
        "assertNotEquals",
        "assertNotNull",
        "assertNotSame",
        "assertNull",
        "assertSame",
        "assertThat",
        "assertThrows",
        "assertThrowsExactly",
        "assertTimeout",
        "assertTimeoutPreemptively",
        "assertTrue",
        "fail",
        "THROW EXCEPTION"
    ]

    for assertion_type in assetions_types:
        for root, dirs, files in os.walk(input_file):
            for file in files:
                if file.endswith(".json") and "overall" not in file:
                    file_path = os.path.join(root, file)
                    with open(file_path, 'r') as f:
                        data = json.load(f)
                        if assertion_type in data.keys():
                            if data[assertion_type] > 0 and file.replace('.json', '') not in projects_list:
                                projects_list.append(file.replace('.json', ''))
        with open(os.path.join(output_folder, assertion_type + ".json"), 'w') as f:
            json.dump(projects_list, f, indent=4)