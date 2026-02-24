pipeline {
    agent none
    parameters {
        string(name: 'SUBDIRECTORY', defaultValue: '', description: 'Subdirectory')
    }
    stages {
        stage('Create Tar Files') {
            agent { label 'PP6_hbp_tapebackup' }
            steps {
                script {
                    // Set build description early with the SUBDIRECTORY parameter
                    currentBuild.description = "Biosample: ${params.SUBDIRECTORY}"
                    
                    def target_dir = "/mnt/remote/tapebackup/staging/${params.SUBDIRECTORY}"
                    echo "Creating tar files in directory: ${target_dir}"
                    sh "/mnt/remote/tapebackup/jenkins-pipeline/dev/code/tar_test.sh ${target_dir} ${target_dir}"
                    // Archive manifest
                    sh """
                        mkdir -p build
                        cp '${target_dir}/tar_creation_manifest.txt' build/tar_creation_manifest.txt
                    """
                }
                archiveArtifacts artifacts: 'build/tar_creation_manifest.txt', allowEmptyArchive: false
            }
        }
        
        stage('Trigger Batch Info Creation') {
            steps {
                script {
                    // Trigger the downstream job with the SUBDIRECTORY parameter
                    build job: '3_Batch_Info_Creation', 
                          parameters: [
                              string(name: 'SUBDIRECTORY', value: params.SUBDIRECTORY)
                          ],
                          wait: true  // Set to false if you don't want to wait for completion
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