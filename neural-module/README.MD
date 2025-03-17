# Neural module
This module is responsible for training and testing the machine learning models representing the neural module
of the tracto program.

## Setup

To set up and run the model, the following requirements must be satisfied:

* `Python 3.9`
* `Pip`

The model has been trained on a GPU `Nvidia A-100` with `80GB` of memory (vRAM) and `147GB` of RAM.

At inference time, the model requires a GPU with at least `24GB` of vRAM.

### 1. Environment

In order to set up the environment is not strictly necessary, but recommended to create a [_venv_](https://docs.python.org/3/library/venv.html)
or [_conda_](https://docs.conda.io/en/latest/) environment.
In the following section we will provide the general information to configure the environment.

### 1.A.1 Create and activate _venv_ environment

Follow the instructions to properly create and activate the environment

1. Open a terminal and move to the `neural-module` directory.
2. Create the _venv_ environment with `Python 3.9`:
   ```shell
   python3 -m venv .venv
   ```
   The command will generate a _venv_ environment within `neural-module/.venv`.
3. Activate the _venv_ environment:
   ```shell
   source .venv/bin/activate
   ```
4. Upgrade _pip_ and _setuptools_ to the latest version:
   ```shell
   pip install --upgrade pip setuptools
   ```
### 1.A.2 Install the requirements

Install the dependencies within the active _venv_ environment, using the following command:

   ```shell
  python3 -m pip install --upgrade -r requirements.txt --target=[path_to_tracto]/neural-module/.venv/lib/python3.9/site-packages
   ```

where:

   * `path_to_tracto` must be substituted the absolute path to the replication package repository

### 1.B.1 Install Conda

The reader can find all the instructions to properly install and setup _conda_, on the official [user guide](https://docs.conda.io/projects/conda/en/stable/user-guide/install/index.html).

We provide an example on how to install conda on Linux operating systems. The installation is relatively similar for
macOS and Windows systems.

1. Open a terminal and type the following command to download the installer:
    ```shell
    wget -P [path_to_destination_directory] [conda_download_http_link]
    ```
    * `path_to_destination_directory` - represents the destination path where the installer will be downloaded.
    * `conda_download_http_link` - is the link of the installer. You can choose the installer [here](https://docs.conda.io/en/latest/miniconda.html#linux-installers), copying the corresponding url.

2. Execute the installer:
    ```shell
    bash [fullname_of_the_installer]
    ```
   * `fullname_of_the_installer` - is the whole name of the downloaded installer (it is a _.sh_ file, like for example
     _Miniconda3-py39_23.3.1-0-Linux-aarch64.sh_)

3. Close and re-open the terminal at the end of the installation. Check _conda_ has been successfully installated typing the command:
    ```shell
    conda --version
    ```

### 1.B.2 Create and activate _conda_ environment

4. Create a new conda environment named "_tracto_" for the **Tracto** project with Python version 3.9 and pip.
   ```bash
    conda create --name tracto python=3.9 pip
    ```

5. Activate the conda environment
    ```bash
    conda activate tracto
    ```

### 1.B.3 Install the requirements

6. Move to the `neural-module` folder of the `tracto` project
    ```bash
    cd [path_to_the_tracto_folder]/neural-module
    ```

7. Install all the required dependencies
    ```shell
    pip install -r requirements.txt
    ```

## E-Wash
The E-wash script parses the oracles and tokens datasets to generate the input for the neural module.
To run the script, move to the `neural-module` folder and execute the following command:

```shell
python3 ./scripts/preprocessing/ewash.py [path_to_dataset_file] [path_to_output_dir] {[path_to_configuration_file]}
```

where:

* `path_to_dataset_file` - is the path to the (oracles/tokens) dataset file to parse.
* `path_to_output_dir` - is the path to the output directory where to save the output file and the statistics.
* `path_to_configuration_file` - is the path to the e-wash configuration file.

The script will process the default E-Wash configuration file (in `./src/resources/ewash_config.json`), if not specified 
otherwise.

# RQ1

To replicate the results of RQ1, set up the parameters and hyperparameters in the file `scripts/bash/inference/infer-pretrained.sh`
(specifiying the model type, the batch size, the maximum length of the input, etc.) and run the following command from the
`neural-module` directory:

```shell
bash ./scripts/bash/inference/infer-pretrained.sh
```