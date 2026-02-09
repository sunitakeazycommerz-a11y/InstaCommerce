terraform {
  backend "gcs" {
    bucket = "instacommerce-terraform-state"
    prefix = "environments"
  }
}
