# ─────────────────────────────────────────────
# Network: reuse o VPC default do projeto
# ─────────────────────────────────────────────
data "google_compute_network" "default" {
  name = "default"
}

data "google_compute_subnetwork" "default" {
  name   = "default"
  region = "us-central1"
}

# ─────────────────────────────────────────────
# Cluster GKE Autopilot
# ─────────────────────────────────────────────
resource "google_container_cluster" "htc_cluster" {
  name     = "htc-cluster"
  location = "us-central1"

  enable_autopilot = true

  deletion_protection = false

  addons_config {
    gcs_fuse_csi_driver_config {
      enabled = true
    }
  }
}

resource "google_storage_bucket" "htc_storage" {
  name          = "htc-simulation-data-unique"
  location      = "us-central1"
  force_destroy = true # Cuidado: apaga os dados ao dar destroy
}

# Bucket para output dos relatórios da simulação
# Recebe JSONL dos pods (via GCS FUSE) e Parquet do Kafka Connect
# Lifecycle: move para Nearline depois de 30 dias (~50% mais barato)
resource "google_storage_bucket" "htc_output" {
  name          = "htc-simulation-output-unique"
  location      = "us-central1"
  force_destroy = true

  lifecycle_rule {
    condition {
      age = 30 # dias
    }
    action {
      type          = "SetStorageClass"
      storage_class = "NEARLINE"
    }
  }

  lifecycle_rule {
    condition {
      age = 180 # 6 meses
    }
    action {
      type          = "SetStorageClass"
      storage_class = "COLDLINE"  # ~$0.004/GB/mês
    }
  }
}

# Service Account no GCP (A identidade que o Pod assumirá)
resource "google_service_account" "htc_sa" {
  account_id   = "htc-simulator-sa"
  display_name = "Service Account para o Simulador HTC"
}

# Permissão para a SA mexer no Bucket de input
resource "google_storage_bucket_iam_member" "bucket_admin" {
  bucket = google_storage_bucket.htc_storage.name
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${google_service_account.htc_sa.email}"
}

# Permissão para a SA escrever no Bucket de output
resource "google_storage_bucket_iam_member" "output_bucket_admin" {
  bucket = google_storage_bucket.htc_output.name
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${google_service_account.htc_sa.email}"
}

# Workload Identity: Vincula a SA do Google com a SA do Kubernetes
resource "google_service_account_iam_member" "workload_identity_user" {
  service_account_id = google_service_account.htc_sa.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "serviceAccount:simedape-362519.svc.id.goog[default/htc-service-account]"
}

# ─────────────────────────────────────────────
# APIs necessárias (habilitação automática)
# ─────────────────────────────────────────────
resource "google_project_service" "managed_kafka_api" {
  service            = "managedkafka.googleapis.com"
  disable_on_destroy = false
}

resource "google_project_service" "container_api" {
  service            = "container.googleapis.com"
  disable_on_destroy = false
}

# ─────────────────────────────────────────────
# Google Managed Apache Kafka
# 100% compatível com a API Kafka — sem mudanças no código da simulação.
# Os custos são cobrados por vCPU/h e GB de storage.
# Mínimo viável: 3 vCPU / 3 GiB RAM (menor configuração permitida).
# ─────────────────────────────────────────────
resource "google_managed_kafka_cluster" "htc_kafka" {
  cluster_id = "htc-kafka"
  location   = "us-central1"

  depends_on = [google_project_service.managed_kafka_api]

  capacity_config {
    vcpu_count   = 3
    memory_bytes = 3221225472 # 3 GiB — mínimo suportado
  }

  gcp_config {
    access_config {
      network_configs {
        # Mesma VPC do GKE — acesso interno sem custo de saída
        subnet = "projects/simedape-362519/regions/us-central1/subnetworks/default"
      }
    }
  }

  # Replicação e retenção para dados de relatórios
  rebalance_config {
    mode = "AUTO_REBALANCE_ON_SCALE_UP"
  }

  labels = {
    env     = "simulation"
    purpose = "htc-reports"
  }
}

# ─── Tópicos HTC ─────────────────────────────
# Relatórios da simulação (principal foco de custo/performance)
resource "google_managed_kafka_topic" "htc_reports" {
  cluster    = google_managed_kafka_cluster.htc_kafka.cluster_id
  location   = "us-central1"
  topic_id   = "htc.reports"

  partition_count    = 6   # maior paralelismo para ingestão de relatórios
  replication_factor = 3

  configs = {
    "retention.ms"    = "86400000"  # 24h de retenção
    "compression.type" = "snappy"
  }
}

resource "google_managed_kafka_topic" "htc_events" {
  cluster    = google_managed_kafka_cluster.htc_kafka.cluster_id
  location   = "us-central1"
  topic_id   = "htc.events"

  partition_count    = 6
  replication_factor = 3

  configs = {
    "retention.ms"    = "3600000"   # 1h — eventos são ephemeral
    "compression.type" = "snappy"
  }
}

resource "google_managed_kafka_topic" "htc_commands" {
  cluster    = google_managed_kafka_cluster.htc_kafka.cluster_id
  location   = "us-central1"
  topic_id   = "htc.commands"

  partition_count    = 3
  replication_factor = 3

  configs = {
    "retention.ms" = "3600000"
  }
}

resource "google_managed_kafka_topic" "htc_state_sync" {
  cluster    = google_managed_kafka_cluster.htc_kafka.cluster_id
  location   = "us-central1"
  topic_id   = "htc.state-sync"

  partition_count    = 6
  replication_factor = 3

  configs = {
    "retention.ms"    = "1800000"   # 30 min
    "compression.type" = "snappy"
  }
}

resource "google_managed_kafka_topic" "htc_dynamic_costs" {
  cluster    = google_managed_kafka_cluster.htc_kafka.cluster_id
  location   = "us-central1"
  topic_id   = "dynamic-link-costs"

  partition_count    = 6
  replication_factor = 3

  configs = {
    "retention.ms"    = "600000"    # 10 min — cache de roteamento
    "compression.type" = "snappy"
  }
}

resource "google_managed_kafka_topic" "htc_custom_reports" {
  cluster    = google_managed_kafka_cluster.htc_kafka.cluster_id
  location   = "us-central1"
  topic_id   = "htc.custom-reports"

  partition_count    = 3
  replication_factor = 3

  configs = {
    "retention.ms"    = "172800000" # 48h — relatórios customizados ficam mais tempo
    "compression.type" = "snappy"
  }
}

# ─── IAM: SA do simulador pode produzir e consumir ───
resource "google_project_iam_member" "kafka_client" {
  project = "simedape-362519"
  role    = "roles/managedkafka.client"
  member  = "serviceAccount:${google_service_account.htc_sa.email}"
}