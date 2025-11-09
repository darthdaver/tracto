import sys
import json
import pandas as pd

if __name__ == "__main__":
    inference_file_path = sys.argv[1]

    with open(inference_file_path, 'r') as f:
        inferences = json.load(f)

    classes_fqn_list = []
    test_classes_fqn_list = []

    for inference in inferences:
        test_class_path = inference['test_class_path']
        if "src/test/java" in test_class_path:
            test_class_path = test_class_path.split("src/test/java/")[1]
        elif "src/tests/java" in test_class_path:
            test_class_path = test_class_path.split("src/tests/java/", "")[1]
        elif "src/java/test" in test_class_path:
            test_class_path = test_class_path.split("src/java/test/", "")[1]
        elif "src/test" in test_class_path:
            test_class_path = test_class_path.split("src/test/", "")[1]
        elif "test/java" in test_class_path:
            test_class_path = test_class_path.split("test/java/", "")[1]
        elif "test" in test_class_path:
            test_class_path = test_class_path.split("test/", "")[1]
        elif "unittest" in test_class_path:
            test_class_path = test_class_path.split("unittest/", "")[1]
        else:
            #print(f"Unknown test file path format: {test_class_path}")
            continue
        test_class_fqn = test_class_path.replace('/', '.').replace('.java', '')
        test_classes_fqn_list.append(test_class_fqn)
        classes_fqn_list.append(test_class_fqn.replace("TestSTARSplitTest", ""))
    print(json.dumps({"classes": ','.join(list(set(classes_fqn_list))), "test_classes": ','.join(list(set(test_classes_fqn_list)))}))
