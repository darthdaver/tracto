"""Step 47 probe (run from scripts/symbolic-module, then: dev_tokens.py <jar-46> <jar-47> s47probe s47probe jsoup:ElementTest eec:ExcelReaderTest).
Builds tmp/ab/s47probe: the v3 runs of jsoup and eec (their repo copies, linked) with an oracles dataset holding
only probe datapoints: real test prefixes whose target is replaced by an assertion with a lambda parameter list."""
import json, glob, os
PROBES = {
    'jsoup': ('ElementTestSTARSplitTest', {
        'testTraverseLambda_split_2': 'assertSame(div, div.traverse((node, depth) -> node.attr("id")));',
        'testTraverseLambda_split_1': 'assertSame(div, div.traverse((Node node, int depth) -> node.attr("id")));',
    }),
    'eec': ('ExcelReaderTestSTARSplitTest', {
        'testReader_split_0': 'assertNotNull(testResourceRoot().toFile().listFiles((dir, name) -> name.endsWith(".xlsx")));',
    }),
}
for project, (cls, targets) in PROBES.items():
    source = f'tmp/ab/v3/{project}'
    probe = f'tmp/ab/s47probe/{project}'
    os.makedirs(f'{probe}/out/raw-oracles-dataset-training/{project}', exist_ok=True)
    if not os.path.exists(f'{probe}/repo'):
        os.symlink(os.path.abspath(f'{source}/repo'), f'{probe}/repo')
    selected = None
    for path in glob.glob(f'{source}/out/raw-oracles-dataset-training/{project}/*.json'):
        for tc in json.load(open(path)):
            if tc['testClass']['identifier'] == cls:
                tc['datapoints'] = [dict(dp, target=targets[dp['testPrefix']['identifier']])
                                    for dp in tc['datapoints'] if dp['testPrefix']['identifier'] in targets]
                selected = tc
    assert selected and len(selected['datapoints']) == len(targets), project
    json.dump([selected], open(f'{probe}/out/raw-oracles-dataset-training/{project}/oracles-datapoints-{project}-0.json', 'w'))
    print(project, [(dp['testPrefix']['identifier'], dp['target']) for dp in selected['datapoints']])
