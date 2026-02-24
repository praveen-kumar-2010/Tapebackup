pipeline {
    agent none  // Do not run on the default agent, instead specify below
    parameters {
        string(name: 'SUBDIRECTORY', defaultValue: '', description: 'Enter the subdirectory name under /mnt/remote/analytics')
    }
    stages {
        stage('Initialize') {
            agent none
            steps {
                script {
                    // Set description immediately when pipeline starts
                    currentBuild.description = "Biosample: ${params.SUBDIRECTORY}"
                    echo "Pipeline initialized for subdirectory: ${params.SUBDIRECTORY}"
                }
            }
        }
        stage('Select Node') {
            agent {
                label 'PP6_hbp_tapebackup'  // Specify the label of the node where the directories are available
            }
            steps {
                script {
                    echo "This job is running on the 'PP6_hbp_tapebackup' agent."
                    echo "Selected subdirectory: ${params.SUBDIRECTORY}"
                }
            }
        }
        stage('Split Files into Chunks') {
            agent {
                label 'PP6_hbp_tapebackup'  // Use the same node for the split operation
            }
            steps {
                script {
                    def root_folder = "/mnt/remote/analytics"
                    def staging_folder = "/mnt/remote/tapebackup/staging"  // Updated path for staging folder
                    def selected_subdirectory = "${root_folder}/${params.SUBDIRECTORY}"
                    def target_directory = "${staging_folder}/${params.SUBDIRECTORY}"
                    echo "Splitting files in directory: ${selected_subdirectory}"
                    echo "Target directory for chunk files: ${target_directory}"
                    // Specify the full path to the split3_files.sh script
                    def split_script = "/mnt/remote/tapebackup/jenkins-pipeline/dev/code/splitltr.sh"
                    echo "Running split3_files.sh script from: ${split_script}"
                    // Execute the split3_files.sh script
                    sh """
                        ${split_script} ${selected_subdirectory} ${target_directory}
                    """
                }
            }
        }
        stage('Trigger Tar Creation') {
            agent none
            steps {
                script {
                    // Trigger tar creation job after splitting files (non-blocking)
                    build job: '2_Test_1TB_Tar_Creation', 
                          parameters: [string(name: 'SUBDIRECTORY', value: params.SUBDIRECTORY)],
                          wait: false  // Don't wait for the job to complete
                }
            }
        }
    }
    post {
        always {
            echo 'Pipeline completed.'
        }
    }
}