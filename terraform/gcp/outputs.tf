# DESATIVADO: Kafka não está sendo usado
# output "kafka_bootstrap_address" {
#   description = "Endereço bootstrap do Managed Apache Kafka — use como HTC_KAFKA_BOOTSTRAP_SERVERS no K8s"
#   value       = "bootstrap.${google_managed_kafka_cluster.htc_kafka.cluster_id}.us-central1.managedkafka.simedape-362519.cloud.goog:9092"
#   sensitive   = false
# }

# output "kafka_cluster_id" {
#   description = "ID do cluster Kafka gerenciado"
#   value       = google_managed_kafka_cluster.htc_kafka.cluster_id
# }

output "gke_cluster_name" {
  description = "Nome do cluster GKE"
  value       = google_container_cluster.htc_cluster.name
}

output "gke_cluster_endpoint" {
  description = "Endpoint do cluster GKE (para kubectl)"
  value       = google_container_cluster.htc_cluster.endpoint
  sensitive   = true
}

output "htc_sa_email" {
  description = "Service Account email do simulador"
  value       = google_service_account.htc_sa.email
}

output "gcs_bucket_name" {
  description = "Nome do bucket GCS com os dados de simulação (input)"
  value       = google_storage_bucket.htc_storage.name
}

output "gcs_output_bucket_name" {
  description = "Nome do bucket GCS para output dos relatórios"
  value       = google_storage_bucket.htc_output.name
}
