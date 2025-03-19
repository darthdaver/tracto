import pickle
import sys
from transformers import AutoTokenizer

if __name__ == "__main__":
    model_name = sys.argv[1]
    dataset_path = sys.argv[2]
    tokenizer = AutoTokenizer.from_pretrained(model_name)
    output_file_path = sys.argv[3]
    num = int(sys.argv[4])

    with open(dataset_path, "rb") as f:
        data = pickle.load(f)

    datapoint_txt = "Input\n"
    datapoint_txt += "-"*5 + "\n"
    datapoint_txt += tokenizer.decode(data['tc_mut'][2]["inputs"][num])
    datapoint_txt += "\n\nOutput\n"
    datapoint_txt += "-"*6 + "\n"
    datapoint_txt += tokenizer.decode(data['tc_mut'][2]["outputs"][num])
    datapoint_txt += "\n"

    print(datapoint_txt)
    with open(output_file_path, "w") as f:
        f.write(datapoint_txt)

