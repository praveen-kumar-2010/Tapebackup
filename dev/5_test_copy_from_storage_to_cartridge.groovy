pipeline {
    agent { label 'PP6_hbp_tapebackup' }
    parameters {
        string(name: 'BATCH_ID', defaultValue: '', description: 'Batch ID to be processed')
        string(name: 'CARTRIDGE_ID', defaultValue: '', description: 'Cartridge ID to process the batch')
        string(name: 'FILES', defaultValue: '', description: 'Comma-separated list of files to copy')
        string(name: 'SUBDIRECTORY', defaultValue: '', description: 'Subdirectory inside staging')
        string(name: 'STAGING_DIR', defaultValue: '/mnt/remote/tapebackup/staging', description: 'Base staging directory')
        string(name: 'DRIVE_NUMBER', defaultValue: '', description: 'Drive number (0, 1, or 2)')
    }
    stages {
        stage('Set Description') {
            steps {
                script {
                    // Set the description as "biosample: SUBDIRECTORY - BATCH_ID - CARTRIDGE_ID"
                    currentBuild.description = "biosample: ${params.SUBDIRECTORY} - ${params.BATCH_ID} - ${params.CARTRIDGE_ID}"
                    echo "Build description set to: ${currentBuild.description}"
                }
            }
        }

        stage('Copy Files') {
            steps {
                script {
                    def files = params.FILES.split(',')
                    echo "Processing batch: ${params.BATCH_ID} on cartridge: ${params.CARTRIDGE_ID}"
                    def destDir = "/mnt/tape/${params.CARTRIDGE_ID}/${params.SUBDIRECTORY}"
                    sh "mkdir -p '${destDir}'"
                    files.each { file ->
                        def cleanFile = file.trim().replaceAll('^"|"$', '')
                        def sourceFile = cleanFile.startsWith("/") ? cleanFile : "${params.STAGING_DIR}/${params.SUBDIRECTORY}/${cleanFile}"
                        echo "Copying file: ${cleanFile} to cartridge ${params.CARTRIDGE_ID} at ${destDir}..."
                        sh """
                            if [ -f '${sourceFile}' ]; then
                                echo '⏳ Copying ${sourceFile} to ${destDir}...'
                                cp -v '${sourceFile}' '${destDir}/'
                                echo '✅ File copied: ${cleanFile}'
                            else
                                echo '❌ File not found: ${sourceFile}'
                            fi
                        """
                    }
                    echo "Batch ${params.BATCH_ID} processed successfully on cartridge ${params.CARTRIDGE_ID} at ${destDir}"

                    build job: '6_Copy_from_cartridge_to_storage',
                          parameters: [
                              string(name: 'SUBDIRECTORY', value: params.SUBDIRECTORY),
                              string(name: 'BATCH_ID', value: params.BATCH_ID),
                              string(name: 'CARTRIDGE_ID', value: params.CARTRIDGE_ID),
                              string(name: 'FILES', value: params.FILES),
                              string(name: 'DRIVE_NUMBER', value: params.DRIVE_NUMBER)
                          ], wait: false
                }
            }
        }
    }
}
