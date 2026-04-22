terraform {
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.0"
    }
  }
}

provider "google" {
  project = "your-project-id" # Substitua pelo ID do seu projeto GCP
  region  = "us-central1"
}