# We use data/train_all

```bash
cd PROJECT_ROOT/classify/
```

## Dataset statistics

Change the label for negative examples to be -1 (original 0).

```bash
awk '{ sub(/0$/, "-1", $1) }1' train_all
```


Some statistics

```bash
# Total number of samples
wc -l data/train_all # 4284
wc -l data/val_all # 535
wc -l data/test_all # 539

# Positive/negative samples
cut -d' ' -f1 data/train_all | sort | uniq -c # 50/4234, imbalanced classes
cut -d' ' -f1 data/val_all | sort | uniq -c # 14/521
cut -d' ' -f1 data/test_all | sort | uniq -c # 11/528
```

The classes in train_all are very imbalanced. Ideally, we want **balanced** classes in the preparation of training data.


## Extend libsvm library

We use libsvm-3.23. The original version only provides the evaluation of classification accuracy. In addition, we want to obtain other evaluation metrics. Thus, we modify the source code based on [this instruction](https://www.csie.ntu.edu.tw/~cjlin/libsvmtools/eval/index.html). Note that our extension is designed only for binary-class C-SVM with labels {1,-1}. Multi-class, regression and probability estimation are not supported. 

In eval.cpp, we set validation_function to be ap (Average Precision). In the scenario of highly-unbalanced data (such as information retrieval area), AP metric is commonly used.



## Check format

```bash
python libsvm-3.23/tools/checkdata.py data/train_all # pass
python libsvm-3.23/tools/checkdata.py data/val_all # pass
python libsvm-3.23/tools/checkdata.py data/test_all # pass
```

## Grid search

The search is w.r.t. the AP metric (done through cross-validation).

```bash
python libsvm-3.23/tools/grid.py data/train_all
# best c=128.0, g=8.0, rate=15.9103 (AP)
```

## Train and validate

Train the model using the parameters (c, g) obtained from grid search. For now, set the weight for positive & negative classes to be 1

```bash
./libsvm-3.23/svm-train -c 128.0 -g 8.0 -w1 1 -w-1 1 data/train_all models/train_all_c128.0_g8.0_wp1_wn1.model
```

Test the model performance

```bash
./libsvm-3.23/svm-predict data/val_all models/libsvm/train_all_c128.0_g8.0_wp1_wn1.model models/libsvm/val_all_c128.0_g8.0_wp1_wn1.out
# Accuracy = 97.757% (523/535)
# AUC = 0.385385
# Precision = 100% (2/2)
# Recall = 14.2857% (2/14)
# FPR = 0% (0/521)
# FNR = 85.7143% (12/14)
# F-score = 0.25
# BAC = 0.571429
# AP = 0.170926
```

If we predict all samples as negative for val_all, the accuracy will be 521/535 = 0.9738318. Thus, the trained model is slightly better.

We enforce unequal weights to the two classes. The penalty for class 1 should be larger (to reduce FNR).


-w1 2

```bash
./libsvm-3.23/svm-train -c 128.0 -g 8.0 -w1 2 -w-1 1 data/train_all models/libsvm/train_all_c128.0_g8.0_wp2_wn1.model
./libsvm-3.23/svm-predict data/val_all models/libsvm/train_all_c128.0_g8.0_wp2_wn1.model models/libsvm/val_all_c128.0_g8.0_wp2_wn1.out
# Accuracy = 97.757% (523/535)
# AUC = 0.456677
# Precision = 100% (2/2)
# Recall = 14.2857% (2/14)
# FPR = 0% (0/521)
# FNR = 85.7143% (12/14)
# F-score = 0.25
# BAC = 0.571429
# AP = 0.196783
```


-w1 3

```bash
./libsvm-3.23/svm-train -c 128.0 -g 8.0 -w1 3 -w-1 1 data/train_all models/libsvm/train_all_c128.0_g8.0_wp3_wn1.model
./libsvm-3.23/svm-predict data/val_all models/libsvm/train_all_c128.0_g8.0_wp3_wn1.model models/libsvm/val_all_c128.0_g8.0_wp3_wn1.out
# Accuracy = 97.757% (523/535)
# AUC = 0.447354
# Precision = 100% (2/2)
# Recall = 14.2857% (2/14)
# FPR = 0% (0/521)
# FNR = 85.7143% (12/14)
# F-score = 0.25
# BAC = 0.571429
# AP = 0.195098
```


-w1 4

```bash
./libsvm-3.23/svm-train -c 128.0 -g 8.0 -w1 4 -w-1 1 data/train_all models/libsvm/train_all_c128.0_g8.0_wp4_wn1.model
./libsvm-3.23/svm-predict data/val_all models/libsvm/train_all_c128.0_g8.0_wp4_wn1.model models/libsvm/val_all_c128.0_g8.0_wp4_wn1.out
# Accuracy = 97.757% (523/535)
# AUC = 0.456814
# Precision = 100% (2/2)
# Recall = 14.2857% (2/14)
# FPR = 0% (0/521)
# FNR = 85.7143% (12/14)
# F-score = 0.25
# BAC = 0.571429
# AP = 0.216624
```


-w1 5

```bash
./libsvm-3.23/svm-train -c 128.0 -g 8.0 -w1 5 -w-1 1 data/train_all models/libsvm/train_all_c128.0_g8.0_wp5_wn1.model
./libsvm-3.23/svm-predict data/val_all models/libsvm/train_all_c128.0_g8.0_wp5_wn1.model models/libsvm/val_all_c128.0_g8.0_wp5_wn1.out
# Accuracy = 97.757% (523/535)
# AUC = 0.453661
# Precision = 100% (2/2)
# Recall = 14.2857% (2/14)
# FPR = 0% (0/521)
# FNR = 85.7143% (12/14)
# F-score = 0.25
# BAC = 0.571429
# AP = 0.207626
```


-w1 10

```bash
./libsvm-3.23/svm-train -c 128.0 -g 8.0 -w1 10 -w-1 1 data/train_all models/libsvm/train_all_c128.0_g8.0_wp10_wn1.model
./libsvm-3.23/svm-predict data/val_all models/libsvm/train_all_c128.0_g8.0_wp10_wn1.model models/libsvm/val_all_c128.0_g8.0_wp10_wn1.out
# Accuracy = 96.8224% (518/535)
# AUC = 0.421716
# Precision = 20% (1/5)
# Recall = 7.14286% (1/14)
# FPR = 0.767754% (4/521)
# FNR = 92.8571% (13/14)
# F-score = 0.105263
# BAC = 0.531876
# AP = 0.129379
```


Thus, we choose (c, g, wp, wn) as (128.0, 8.0, 4, 1).


## Enable probabilistic estimates

We need to download a fresh package since our modified version does not support probabilistic estimates (we replace predict() as binary_class_predict() in svm-predict.c).

```bash
./libsvm-3.23_v2/svm-train -b 1 -c 128.0 -g 8.0 -w1 4 -w-1 1 data/train_all models/libsvm/train_all_c128.0_g8.0_wp4_wn1_b1.model
./libsvm-3.23_v2/svm-predict -b 1 data/val_all models/libsvm/train_all_c128.0_g8.0_wp4_wn1_b1.model models/libsvm/val_all_c128.0_g8.0_wp4_wn1_b1.out
./libsvm-3.23_v2/svm-predict -b 1 data/train_all models/libsvm/train_all_c128.0_g8.0_wp4_wn1_b1.model models/libsvm/train_all_c128.0_g8.0_wp4_wn1_b1.out
```


