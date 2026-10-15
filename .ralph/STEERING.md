# Steering

<!--
Critical work the agent must do before any feature task: a broken build, a failing test,
a correction to earlier work. The agent completes these in order and removes each one when
it is done. Leave the list empty when there is nothing to steer.
-->

## IMPORTANT: Storing persistent data
You are running in a containerised sandbox, relying almost entirely on epehmeral storage. Only /workspace is bound to a PVC and will survive a restart.
Anything you write in all other folders, including your home folder, will be lost on restart. Anything you need to keep has to be created in a subfolder of
/workspace, including for example your build toolchain.

If you find that your build toolchain is missing, it may be that you previously didn't follow these instructions. If so, refer to TASK-1 and do the setup again,
in /workspace 
