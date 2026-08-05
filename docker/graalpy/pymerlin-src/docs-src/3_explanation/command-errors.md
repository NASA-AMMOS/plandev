# Command Errors

A command error, sometimes called a command file error, describes the situation in which a message is sent from the ground system to the flight system that results in one of the following responses:
- The spacecraft rejects the command as invalid
- The command triggers a spacecraft fault condition
- The command has an adverse effect on the spacecraft
- The command does not result in the intended spacecraft behavior

The consequence of a command error can range from lost opportunities (e.g. a spacecraft does not record data of a particular event of interest), to loss of mission (the spacecraft is permanently incapacitated).

The flight system’s fault protection subsystem is designed to minimize the consequence of command error by putting the spacecraft into a “safe mode” when certain automatic fault detection, isolation, and recovery (FDIR) mechanisms are triggered. There are, however, command errors that FDIRs are powerless against:
- CFDP 2 delayed packets
- Incorrect inputs to maneuvers
- Unexpected failure modes of FSW
- Incorrect observation timings
- Incorrect Earth vector values
- Concurrent communication of multiple spacecraft in a constellation
- Unintentional sequence deletes
- Sequences that don’t set a new parameter that was added in a new FSW version

## Avoiding Command Errors
In order to reduce the risk of command error, the ground system may perform certain checks before allowing a message to be sent to the spacecraft. These checks range from constraints on the syntax of command files, to some abstract interpretation of the command files, to simulation of these command files. Some desirable characteristics of a ground system check:
- Precision - a check should minimize false positives
- Recall - a check should miss as few command errors as possible
- Interactivity - a check should be fast enough to enable an operations team to make multiple drafts of their command files before the communication deadline

Recall is perhaps the most important characteristic for risk mitigation. When the consequences of command errors are high, ground system checks will skew towards high recall at the cost of lowering their precision. This is the mentality of “if you’re not sure, err on the side of caution.”

The cost of low precision is increased burden on the operations team, which in turn can lead to increased risk of command error. They have to sift through large numbers of false positives, dispositioning many of them as “nominal”. This can lead to complacency, where an operator sees a familiar error, and marks it as a false positive, when in fact something about the environment has changed, making this a true positive. (example: sequences before and after MSL FSW upgrade. False positives that sequences were drafted for the wrong FSW became true positives when the FSW was in fact swapped).

If certain checks can be done with low cost, high precision and high recall, we recommend that they be performed on every command file, as early in the process as feasible. These checks are typically syntactic checks on individual command files. In typical spacecraft command paradigms, this involves inspecting commands and their arguments, such as:
- Command existence checks (does the command you entered actually exist?)
- Argument cardinality checks (did you provide a correct number of arguments, or in the case of key/value pairs, did you provide all of the required keys?)
- Command argument type checks (do the arguments you provided correspond to the types declared by the command definition?)
- Command argument range checks (for enumerations or numeric arguments, are the values you provided within the ranges specified in the command definition?)
- Restricted command checks (has the mission marked the command you entered as approved for use?)

Many checks fall under the category “Don’t execute command X in condition Y” (cite Matt Muszynski). The check for the absence of command X is trivial, and we recommend that that check be performed unconditionally. If command X is rare, this check will have high recall (i.e. most command files will correctly pass this check.) The check for condition Y may range from straightforward, to difficult, to impossible. Importantly, the checks must not be limited to “pass” or “fail” results - there must be a way for the check to indicate an indefinite state, such as “I have found command X, but could not check condition Y”, marking that check for follow-up by another part of the ground system.

## Methods of Validation
- Validating that the contents of a command file match the intent of the author cannot be accomplished via a fixed set of checks. This sort of validation is far more dynamic, and requires interactivity between the author and the ground system. Usually, this involves executing the command file in an environment where the consequence of command file errors is low.
- Validation using spacecraft behavioral models
- Validation using spacecraft resource models
- Validation using flight software on the ground
- Validation using flight hardware on the ground
- Validation in flight

The author can view the results of the command file execution and assess whether the results match their intent. If the results resemble spacecraft telemetry, the author’s assessment can mirror the telemetry assessment they expect to perform once the command file has been executed on board the spacecraft.

> Note: There's a difference between validation of commands sent to the flight system and validation of the behavior of
the whole mission operations system.

## Re-usable command files
Not all command files carry the same consequence of command file error. Prudent spacecraft command language designers try to separate dangerous commands from lower risk ones. This can reduce the burden on ground systems - if it can be proven that a command file has low risk, it’s possible to subject it to less exhaustive verification and validation.

One way to reduce the burden on ground systems is to re-use command files. The command file can be exhaustively validated once using one of the validation methods above and then approved for re-use without further re-validation. This paradigm allows these command files to be subjected to a high level of scrutiny once, reducing the risk of command file error, and then re-used with a lower level of scrutiny, reducing the burden on the operations team.

One risk to keep in mind with re-usable command files is that it is imperative for the operations team to be aware of their operational envelope. An approval to use these sequences should come with a description of the conditions under which it is safe or unsafe to use this command file.

Depending on the flight system, these reusable command files can be re-transmitted as needed by the ground system, or persisted on-board and referenced by name.

Re-usable command files cannot use absolute times - either the ground system or the flight system must be able to compute the execution timing of these files every time they are used.

## Making changes to command files
The situation often arises where a command file has gone through a verification and validation process, been approved, and then for some reason needs to change. These reasons range from:
- The predicted state of the spacecraft when the command file was written differs from the newest predicted state
- The intent of the author has changed
- A new risk was identified that was not addressed during verification and validation
- An optimization was identified, meaning the intent of the command file can be achieved better in some way
  - It can be achieved with less risk, or more reliably
  - It can be achieved more quickly
  - It can be achieved while using fewer resources
  - It can be achieved at a different time, which frees up spacecraft resources to perform a more constrained activity that conflicted with the original time

If the verification and validation process is lightweight enough, the file can be marked as superseded, and a new file can be submitted with the changes applied. However, it may be the case that smaller deviations from the original file can justify skipping some of the checks performed on the original file. Even if all checks are still performed, small differences give the human reviewers a signal for where to focus their attention. This can reduce burden on the operations team, and reduce perceived risk.

A prudent command file author will write their command files in such a way that the most likely changes are small. Small here has two meanings: the change should result in a file that is syntactically identical to the original file in all but a small number of places. It should also be easy to prove that the change does not affect the validity of the majority of the file. (cite Carol Polanskey)

## Integrated vs Partial validation
Some spacecraft operations teams include multiple command file authors. It can be difficult or impossible (or undesirable) to have all of the authors write their command files together. To maximize their autonomy, the process may include independent work time interspersed with key coordination points. The authors must first come to a consensus about how limited spacecraft resources should be allocated. This can be a pointing profile (where is the spacecraft pointed, when?) or a power allocation (I want to do this power intensive activity, which means you can’t also do a power intensive activity until the battery recharges). Having defined a skeleton for the plan, the authors can go off and work on their pieces independently, trusting that if they stay within the guardrails defined by the skeleton, the risk of conflict between their work and another author’s work is low. Put another way, the purpose of the skeleton is to promote the following property: the absence of command errors in a single author’s work implies the lack of command errors in the combined execution of all authors’ work. These guarantees are not bulletproof, and are usually defined informally, and aren’t checked automatically, so there is typically an integrated validation step, where all authors deliver their work to a single place, where the combined execution of all of their command files can be verified and validated.

## Activity Planning
The “plan skeleton” mentioned in the previous section is a high level representation of what the spacecraft is expected to do. High level representations are useful in a few ways:
- They represent a plan in a smaller number of constituent parts, making it more palatable for human understanding, and more tractable for automated analysis
- They define a plan that is correct with respect to the high level definitions of the activities. As long as the implementations of those activities adhere to the expectations set during activity planning, the same correctness properties should hold on the integrated command plan
- They can more directly represent the intent of the plan

How do flight system capabilities impact the planning process?
Spacecraft have been designed with some on-board autonomy for off-nominal situations for a very long time now. The Voyager missions famously had in-depth fault protection responses. Most of this autonomy is focused on keeping the spacecraft healthy and operable - automatic heater controls to keep components within allowable flight temperatures, safe modes for both earth pointing (enable communication for fault recovery) and sun point (use solar panels to recharge for survival).

In nominal operations, the ground system has until recently had more fine-grained control. Command files would include specific times at which the spacecraft should execute specific actions. Any uncertainty in the duration or outcome of an action would be anticipated, and the command file would be written assuming the worst case outcome - either longest duration or worst performance. This mode of operations has low risk of failure - in practice most of these commands have close to the best case duration and performance - but it introduces waste in the form of lost spacecraft time, and also limits the spacecraft’s performance by tying its actions to the ability of the ground system to model the state of the spacecraft and its environment.

Ground systems that support flight systems with more on-board autonomy share some similarities to other ground systems. For one, command files must still be sent to the spacecraft, and telemetry must still be received. Those command files may contain errors that cause the spacecraft to do undesirable things, so it would still be prudent for a ground system to run checks on those command files. However, the contents of those command files, the semantics of their execution, and therefore the kinds of checks that can be performed is where there is the greatest difference.

Any sound checks of command files strive to guarantee that these files will execute without error within the envelope of time uncertainty as well as spacecraft state uncertainty. In less autonomous flight systems, the uncertainty in spacecraft state can be more tightly bounded - there is a comparatively smaller number of things the spacecraft may decide to do. Autonomous systems need guardrails to be operable - the system itself must know more about its safe envelope, so that it can guarantee using onboard checks that the activities it chooses to perform keep it in a safe state.

This means that the ground system has somewhat less responsibility for the safety of the spacecraft (though it must still do what it can), but the ground system still has complete responsibility for validating the intent of the operators. That means it needs some way to simulate a representative range of autonomous decisions and across the range of environmental uncertainty and give the operator a sense for how likely it is that the behavior will match their intent.

## How do you guarantee the safety of an autonomous spacecraft?
- Give it clear constraints
- Limit the actions it can take (maybe avoid autonomous trajectory maneuvers)
- Imbue it with old fashioned FDIRs