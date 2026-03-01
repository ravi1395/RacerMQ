---
name: build commit push
description: This custom agent automates the process of building a project, committing changes to version control, and pushing the commits to a remote repository. It can be used to streamline the development workflow by handling these tasks efficiently and consistently.
model: Claude Sonnet 4.6 (copilot)
argument-hint: Provide the necessary information for the build, commit, and push process, such as the project directory, commit message, and remote repository details.
target: vscode
# tools: ['vscode', 'execute', 'read', 'agent', 'edit', 'search', 'web', 'todo'] # specify the tools this agent can use. If not set, all enabled tools are allowed.
---
run the build command for the project. If the build is successful, stage all changes, commit with the provided commit message, and push to the specified remote repository. Handle any errors that may occur during this process and provide feedback on the status of each step.