variable "project_id" {
  description = "GCP Project ID"
  type        = string
  default     = "simedape-362519"
}

variable "region" {
  description = "GCP Region"
  type        = string
  default     = "us-central1"
}

variable "zone" {
  description = "GCP Zone"
  type        = string
  default     = "us-central1-a"
}

variable "vm_name" {
  description = "VM instance name"
  type        = string
  default     = "htc-vm"
}

variable "machine_type" {
  description = "VM machine type. High-memory options: n2-highmem-32 (256GB), n2-highmem-64 (512GB), n2-highmem-96 (768GB), n2-highmem-128 (864GB), m3-ultramem-64 (976GB)"
  type        = string
  default     = "n2-highmem-96"
}

variable "disk_size_gb" {
  description = "Boot disk size in GB (Docker images + temp files)"
  type        = number
  default     = 500
}

variable "input_bucket" {
  description = "GCS bucket name for simulation input data"
  type        = string
  default     = "htc-simulation-data-unique"
}

variable "output_bucket" {
  description = "GCS bucket name for simulation output (created if it doesn't exist)"
  type        = string
  default     = "htc-vm-output-unique"
}

variable "grant_input_bucket_iam" {
  description = "Set to true only after the input bucket already exists. Avoids 404 on first apply."
  type        = bool
  default     = false
}

# ─────────────────────────────────────────────
# Service Account para a VM
# ─────────────────────────────────────────────
resource "google_service_account" "htc_vm_sa" {
  account_id   = "htc-vm-sa"
  display_name = "HTC VM Service Account"
  project      = var.project_id
}

# Leitura do bucket de input.
# Aplicado apenas quando grant_input_bucket_iam=true para evitar 404
# caso o bucket ainda não exista (criado pelo stack GKE ou pelo upload).
resource "google_storage_bucket_iam_member" "vm_input_reader" {
  count  = var.grant_input_bucket_iam ? 1 : 0
  bucket = var.input_bucket
  role   = "roles/storage.objectViewer"
  member = "serviceAccount:${google_service_account.htc_vm_sa.email}"
}

# ─────────────────────────────────────────────
# Bucket de output exclusivo para a VM
# ─────────────────────────────────────────────
resource "google_storage_bucket" "htc_vm_output" {
  name          = var.output_bucket
  location      = var.region
  force_destroy = true
  project       = var.project_id

  lifecycle_rule {
    condition {
      age = 30
    }
    action {
      type          = "SetStorageClass"
      storage_class = "NEARLINE"
    }
  }

  lifecycle_rule {
    condition {
      age = 180
    }
    action {
      type          = "SetStorageClass"
      storage_class = "COLDLINE"
    }
  }
}

resource "google_storage_bucket_iam_member" "vm_output_admin" {
  bucket = google_storage_bucket.htc_vm_output.name
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${google_service_account.htc_vm_sa.email}"
}

# ─────────────────────────────────────────────
# Firewall: SSH
# ─────────────────────────────────────────────
resource "google_compute_firewall" "htc_vm_ssh" {
  name    = "htc-vm-allow-ssh"
  network = "default"
  project = var.project_id

  allow {
    protocol = "tcp"
    ports    = ["22"]
  }

  source_ranges = ["0.0.0.0/0"]
  target_tags   = ["htc-vm"]
}

# ─────────────────────────────────────────────
# Firewall: Pekko Management + Métricas + Grafana
# Restrinja source_ranges em produção!
# ─────────────────────────────────────────────
resource "google_compute_firewall" "htc_vm_ports" {
  name    = "htc-vm-allow-mgmt"
  network = "default"
  project = var.project_id

  allow {
    protocol = "tcp"
    # 8558-8700: Pekko Management por nó
    # 9101-9200: Prometheus metrics por nó
    # 3000: Grafana dashboard
    ports = ["8558-8700", "9101-9200", "3000"]
  }

  source_ranges = ["0.0.0.0/0"]
  target_tags   = ["htc-vm"]
}

# ─────────────────────────────────────────────
# VM de alta memória
# ─────────────────────────────────────────────
resource "google_compute_instance" "htc_vm" {
  name         = var.vm_name
  machine_type = var.machine_type
  zone         = var.zone
  project      = var.project_id

  tags = ["htc-vm"]

  boot_disk {
    initialize_params {
      # Debian 12 — suporte oficial ao gcsfuse
      image = "debian-cloud/debian-12"
      size  = var.disk_size_gb
      type  = "pd-ssd"
    }
  }

  network_interface {
    network = "default"
    # IP externo efêmero — necessário para SSH e pull de imagens Docker
    access_config {}
  }

  service_account {
    email  = google_service_account.htc_vm_sa.email
    scopes = ["cloud-platform"]
  }

  # Reduz ndots para evitar DNS storm (igual ao GKE)
  metadata = {
    enable-oslogin = "FALSE"
  }

  scheduling {
    on_host_maintenance = "MIGRATE"
    automatic_restart   = true
  }
}

# ─────────────────────────────────────────────
# Outputs
# ─────────────────────────────────────────────
output "vm_name" {
  value       = google_compute_instance.htc_vm.name
  description = "Nome da VM"
}

output "vm_external_ip" {
  value       = google_compute_instance.htc_vm.network_interface[0].access_config[0].nat_ip
  description = "IP externo da VM (para SSH direto)"
}

output "vm_zone" {
  value       = google_compute_instance.htc_vm.zone
  description = "Zona da VM"
}

output "vm_sa_email" {
  value       = google_service_account.htc_vm_sa.email
  description = "Email do Service Account da VM"
}

output "output_bucket" {
  value       = google_storage_bucket.htc_vm_output.name
  description = "Bucket GCS de output"
}
