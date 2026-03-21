provider "google" {
  project = var.project_id
  region  = var.region
}

module "vpc" {
  source        = "../../modules/vpc"
  env           = var.env
  region        = var.region
  subnet_cidr   = var.subnet_cidr
  pods_cidr     = var.pods_cidr
  services_cidr = var.services_cidr
}

module "gke" {
  source              = "../../modules/gke"
  project_id          = var.project_id
  env                 = var.env
  region              = var.region
  network_id          = module.vpc.network_id
  subnetwork_id       = module.vpc.subnetwork_id
  pods_range_name     = module.vpc.pods_range_name
  services_range_name = module.vpc.services_range_name
}

module "cloudsql" {
  source     = "../../modules/cloudsql"
  project_id = var.project_id
  env        = var.env
  region     = var.region
  network_id = module.vpc.network_id
  databases  = var.databases
}

module "memorystore" {
  source     = "../../modules/memorystore"
  project_id = var.project_id
  env        = var.env
  region     = var.region
  network_id = module.vpc.network_id
}

module "iam" {
  source           = "../../modules/iam"
  project_id       = var.project_id
  env              = var.env
  service_accounts = var.service_accounts
}

module "secret_manager" {
  source     = "../../modules/secret-manager"
  project_id = var.project_id
  env        = var.env
  secrets    = var.secrets
}

module "bigquery" {
  source     = "../../modules/bigquery"
  project_id = var.project_id
  env        = var.env
  location   = var.region
}

module "feature_store" {
  source     = "../../modules/feature-store"
  project_id = var.project_id
  env        = var.env
  location   = var.region
  region     = var.region
  network_id = module.vpc.network_id
}

module "data_lake" {
  source     = "../../modules/data-lake"
  project_id = var.project_id
  env        = var.env
  location   = var.region
}

module "dataflow" {
  source     = "../../modules/dataflow"
  project_id = var.project_id
  env        = var.env
  location   = var.region
}

module "kubernetes_secrets" {
  source    = "../../modules/kubernetes-secrets"
  namespace = "default"

  depends_on = [module.gke]
}
